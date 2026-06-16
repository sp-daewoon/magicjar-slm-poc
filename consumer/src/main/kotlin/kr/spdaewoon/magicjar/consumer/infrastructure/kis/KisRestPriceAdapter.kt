package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration

/**
 * KIS REST 현재가 단건 조회 어댑터 — polling fallback 및 Redis warm-up 용도.
 *
 * KIS-first 출처:
 * - TR_ID: FHKST01010100
 * - endpoint: GET /uapi/domestic-stock/v1/quotations/inquire-price
 * - param: fid_cond_mrkt_div_code=J, fid_input_iscd={code}
 * - KIS API reference: docs/operations/kis-api-reference.md §3 #1
 * - Response fields (PriceMetrics):
 *   output.per (PER), output.pbr (PBR), output.eps (EPS), output.bps (BPS)
 *   output.hts_avls (HTS 시가총액 — 억원), output.bstp_kor_isnm (업종 한글명)
 *   output.hts_kor_isnm (종목명), output.rprs_mrkt_kor_name (대표시장 한글명)
 *   output.stck_sdpr (기준가/전일종가), output.stck_hgpr (최고가), output.stck_lwpr (최저가)
 *   output.stck_oprc (시가), output.acml_vol (누적거래량)
 *
 * 또한 KisRestPort.fetchCandles 구현도 담당 (기존 RealKisRestAdapter 역할 consumer 내 추가).
 * consumer 모듈은 api 모듈 어댑터에 의존할 수 없으므로 consumer 자체 구현.
 */
@Component("consumerKisRestPriceAdapter")
class KisRestPriceAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    private val kisRateLimiterPort: KisRateLimiterPort,
    /** appkey/appsecret 헤더 복원용 (KIS 필수 헤더 — 누락 시 500. KisRestCandleAdapter 패턴 정합). */
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val secretCipher: SecretCipher,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisRestPort,
    KisRestPriceMetricsPort {
    private val log = LoggerFactory.getLogger(KisRestPriceAdapter::class.java)

    /**
     * KIS 필수 헤더 appkey/appsecret resolve — userId 보유 credential 우선, 부재 시 첫 REAL 행 fallback.
     * KDoc 명세에만 있고 실제 헤더 미전송으로 KIS 500을 받던 결함 복원(2026-06-10 라이브 실측).
     * ByteArray는 사용 직후 zero-fill wipe (KisRestCandleAdapter 패턴 정합).
     */
    private fun resolveAppKeys(userId: String): Pair<String, String> {
        val creds = credentialRepository.findAllByAccountType(AccountType.REAL.name)
        val cred =
            creds.firstOrNull { it.id.userId == userId }
                ?: creds.firstOrNull()
                ?: error("KIS REAL credential 없음 — userId=$userId")
        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            String(appKey) to String(secretKey)
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * KIS REST 현재가 단건 조회 (FHKST01010100 inquire-price).
     *
     * KIS-first 출처: kis-api-reference.md §3 #1, external-api-policy.md §2-4
     * Header: authorization (Bearer {access_token}), appkey, appsecret, tr_id=FHKST01010100, custtype=P
     * Response: output.stck_prpr (현재가), output.acml_vol (누적거래량)
     *
     * timeout: 3s (dispatch-spec §3-2-C 정합)
     */
    override fun fetchPrice(
        userId: String,
        code: String,
    ): TickEvent = fetchPriceInternal(userId = userId, code = code, priority = KisCallPriority.USER_PATH)

    /**
     * Watchlist polling 전용 현재가 조회.
     *
     * 관심종목 polling 은 사용자 주문/잔고 hot path 가 아니므로 USER_PATH 버킷을 쓰지 않는다. 2026-06-15 REAL
     * 세션에서 watchlist polling 이 USER bucket 을 소모해 주문경로 잔고조회(EGW00215)와 경합한 것을 확인했다.
     * ADR 0116 3-tier 의도대로 INGESTION bucket(shared/bg)으로 낮춰 자본 경로 USER bucket 을 보호한다.
     */
    fun fetchPollingPrice(
        userId: String,
        code: String,
    ): TickEvent = fetchPriceInternal(userId = userId, code = code, priority = KisCallPriority.INGESTION)

    private fun fetchPriceInternal(
        userId: String,
        code: String,
        priority: KisCallPriority,
    ): TickEvent {
        val accountType = AccountType.REAL
        val baseUrl = kisBaseProd
        val token = kisAuthAdapter.getAccessToken(userId, accountType)
        val (appKeyStr, appSecretStr) = resolveAppKeys(userId)

        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   acquire를 try 안으로 옮기고 signalSent flag로 통계 시그널 미발행 path 감시.
        //   기존 결함: acquire 후 OOM/interrupt/parse error 등 예외 시 통계 0 → drift.
        var signalSent = false
        return try {
            // Rate Limit acquire (spec PR #564 §2-2 LOCK). 사용자 조회는 USER_PATH, watchlist polling 은 INGESTION.
            kisRateLimiterPort.acquire(priority, timeoutMillis = 3000L, trId = "FHKST01010100")

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri(
                        "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price" +
                            "?fid_cond_mrkt_div_code=J&fid_input_iscd=$code",
                    ).header("authorization", "Bearer $token")
                    .header("appkey", appKeyStr)
                    .header("appsecret", appSecretStr)
                    .header("tr_id", "FHKST01010100")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(3))
                    .block() as? Map<String, Any>
                    ?: error("KIS inquire-price 응답 없음 — code=$code")

            val output =
                response["output"] as? Map<String, Any>
                    ?: error("KIS inquire-price output 없음 — code=$code")

            val priceStr = output["stck_prpr"]?.toString() ?: "0"
            val volumeStr = output["acml_vol"]?.toString() ?: "0"

            kisRateLimiterPort.onSuccess()
            signalSent = true
            TickEvent(
                code = code,
                price = BigDecimal(priceStr.ifBlank { "0" }),
                volume = volumeStr.ifBlank { "0" }.toLongOrNull() ?: 0L,
                ts = Clock.System.now(),
                source = "polling",
                stale = false,
            )
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS inquire-price Rate Limit — onTpsExceeded: code={} status={}", code, e.statusCode.value())
            }
            log.error(
                "KIS inquire-price 실패: code={} status={} body={}",
                code,
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisCallAccounting] inquire-price acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} priority={}",
                    code,
                    priority,
                )
            }
        }
    }

    /**
     * KIS REST 기간별 캔들 조회 (FHKST03010100 inquire-daily-itemchartprice).
     * consumer 모듈용 — api 모듈 RealKisRestAdapter와 독립적으로 구현.
     * 1차 MVP: 기본 구현 (1wk·1mo·1y 백필 용도는 backend-core PR-9a에서 구현).
     */
    override fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle> {
        // 1차 MVP: consumer 내 캔들 REST 백필은 backend-core PR-9a KisRestCandleAdapter 담당
        // 본 어댑터는 fetchPrice만 사용 — fetchCandles는 UnsupportedOperation (consumer 단독)
        log.warn("KisRestPriceAdapter.fetchCandles: consumer 모듈은 fetchPrice 전용 — fetchCandles는 backend-core PR-9a 담당")
        return emptyList()
    }

    /**
     * KIS REST 가격·메타 조회 — inquire-daily-itemchartprice (FHKST03010100) output1.
     *
     * 2026-05-20 endpoint 교체 사유: 기존 inquire-price(FHKST01010100) stck_prpr이 NXT 시간외
     * 거래를 반영해 정규장 종가와 다른 가격 반환 (SK하이닉스 사례). candle adapter와 동일 endpoint
     * 사용으로 정규장 source 정합.
     *
     * output1: 종목 메타 + 당일 시세 (스칼라). output2(일봉 시계열)는 candle adapter 영역.
     * BPS는 output1에 없음 → null graceful. industry·market은 instrument 테이블 1순위.
     *
     * timeout: 3s (dispatch-spec §3-2 정합)
     */
    override fun fetchPriceMetrics(
        userId: String,
        code: String,
    ): PriceMetrics {
        val accountType = AccountType.REAL
        val baseUrl = kisBaseProd
        val token = kisAuthAdapter.getAccessToken(userId, accountType)
        val (appKeyStr, appSecretStr) = resolveAppKeys(userId)

        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val startDate = today.minusDays(7).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val endDate = today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   acquire를 try 안으로 옮기고 signalSent flag로 통계 시그널 미발행 path 감시.
        var signalSent = false
        return try {
            // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH priority — metrics path)
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = "FHKST03010100")

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri(
                        "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice" +
                            "?fid_cond_mrkt_div_code=J" +
                            "&fid_input_iscd=$code" +
                            "&fid_input_date_1=$startDate" +
                            "&fid_input_date_2=$endDate" +
                            "&fid_period_div_code=D" +
                            "&fid_org_adj_prc=0",
                    ).header("authorization", "Bearer $token")
                    .header("appkey", appKeyStr)
                    .header("appsecret", appSecretStr)
                    .header("tr_id", "FHKST03010100")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(3))
                    .block() as? Map<String, Any>
                    ?: error("KIS inquire-daily-itemchartprice (metrics) 응답 없음 — code=$code")

            val output1 =
                response["output1"] as? Map<String, Any>
                    ?: error("KIS inquire-daily-itemchartprice (metrics) output1 없음 — code=$code")

            // KIS 영업일자(stck_bsop_date)는 output1에 없고 output2[] 시계열 행에만 있음.
            //   KIS-first 출처: open-trading-api inquire_daily_itemchartprice output2[] (KisRestCandleAdapter parseKisDailyResponse 정합).
            //   output2[]는 최신→과거 DESC 순서 → 첫 행이 최신 영업일자.
            //   용도: seedTodayCandle의 새벽 유령 일봉 방지 가드(영업일자 != 오늘 시드 skip). 추가 KIS 콜 0(같은 응답 재활용).
            @Suppress("UNCHECKED_CAST")
            val businessDate =
                (response["output2"] as? List<Any?>)
                    ?.asSequence()
                    ?.mapNotNull {
                        (it as? Map<String, Any>)
                            ?.get("stck_bsop_date")
                            ?.toString()
                            ?.trim()
                            ?.ifBlank { null }
                    }?.firstOrNull()

            kisRateLimiterPort.onSuccess()
            signalSent = true
            PriceMetrics(
                per = output1["per"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                pbr = output1["pbr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                eps = output1["eps"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                bps = null, // output1에 없음 — nullable graceful
                marketCap = output1["hts_avls"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                industry = null, // output1에 없음 — instrument 테이블 1순위
                name = output1["hts_kor_isnm"]?.toString()?.ifBlank { null },
                market = null, // output1에 없음 — instrument 테이블 1순위
                currentPrice = output1["stck_prpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                previousClose = output1["stck_prdy_clpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                priceChange = output1["prdy_vrss"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                changeRate = output1["prdy_ctrt"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                changeSign = output1["prdy_vrss_sign"]?.toString()?.ifBlank { null },
                high = output1["stck_hgpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                low = output1["stck_lwpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                open = output1["stck_oprc"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                volume = output1["acml_vol"]?.toString()?.ifBlank { null }?.toLongOrNull(),
                businessDate = businessDate,
            )
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일.
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn(
                    "KIS inquire-daily-itemchartprice (metrics) Rate Limit — onTpsExceeded: code={} status={}",
                    code,
                    e.statusCode.value(),
                )
            }
            log.error(
                "KIS inquire-daily-itemchartprice (metrics) 실패: code={} status={} body={}",
                code,
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] PriceMetrics(consumer) acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={}",
                    code,
                )
            }
        }
    }

    companion object {
        fun accountTypeForMode(mode: String): AccountType = RuntimeModeParser.accountTypeOrMock(mode)
    }
}
