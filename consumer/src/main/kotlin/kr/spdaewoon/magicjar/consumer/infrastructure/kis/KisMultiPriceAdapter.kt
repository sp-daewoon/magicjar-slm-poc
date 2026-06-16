package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
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
 * KIS 멀티시세(관심종목 그룹별 시세) 조회 어댑터 (ADR 0118 R1 — MARKET_DAILY 저녁/장전 snapshot).
 *
 * KIS `intstock-multprice` (TR `FHKST11300006`) — **한 콜에 최대 30종목** 묶음 시세.
 * 보통주 ~3,000 ÷ 30 = ~100콜로 전종목 그날 일봉 1개를 확보한다(ADR 0118 §1-3 경제성 근거).
 *
 * KIS-first raw spec (헌법 §3 의무 인용 · 2026-06-09 raw fetch 검증):
 *   - URL path: /uapi/domestic-stock/v1/quotations/intstock-multprice
 *   - tr_id: FHKST11300006
 *   - 요청: FID_COND_MRKT_DIV_CODE_N + FID_INPUT_ISCD_N 쌍 (N=1..30). _1 필수, _2~_30 옵션.
 *   - 응답 output 필드 (COLUMN_MAPPING verbatim):
 *       inter2_oprc · inter2_hgpr · inter2_lwpr · inter2_prpr · inter2_prdy_clpr · inter2_sdpr ·
 *       acml_vol · acml_tr_pbmn · inter_shrn_iscd · kospi_kosdaq_cls_name · mrkt_trtm_cls_name · hour_cls_code
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py
 *        https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/chk_intstock_multprice.py
 *
 * rate-limit (ADR 0116 — 3-tier 버킷):
 *   멀티시세 snapshot은 대량 호출 BG 성격 → KisCallPriority.BACKFILL (BG 버킷 3/s).
 *   ADR 0118 §2-C·§5-1 LOCK — USER_PATH·INGESTION 절대 침범 0 (사용자·자본 path 영향 0).
 *
 * 정보 수집 = prod REAL credential 단일 채널 (모의/실전 분기는 주문 라우터 한정 — 정책 LOCK).
 * 결손 graceful: 응답 0건/누락 종목은 빈 list 또는 skip — mock 합성 0 (feedback_no_mock_data).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class KisMultiPriceAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) : KisMultiPricePort {
    private val log = LoggerFactory.getLogger(KisMultiPriceAdapter::class.java)

    companion object {
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py
        private const val TR_ID = "FHKST11300006"
        private const val ENDPOINT = "/uapi/domestic-stock/v1/quotations/intstock-multprice"

        /** 한 콜 최대 종목 수 — raw spec: FID_INPUT_ISCD_N N=1..30. */
        const val MAX_SYMBOLS_PER_CALL: Int = 30

        private val CODE_REGEX = Regex("^\\d{6}$")
    }

    override fun fetchMultiPrice(
        codes: List<String>,
        mrktDivCode: String,
        priority: KisCallPriority,
    ): List<MultiPriceQuote> {
        val validCodes =
            codes
                .asSequence()
                .map { it.trim() }
                .filter { CODE_REGEX.matches(it) }
                .distinct()
                .toList()
        if (validCodes.isEmpty()) {
            log.warn("KIS 멀티시세: 유효 종목코드 0건 — 빈 결과 반환")
            return emptyList()
        }

        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.warn("KIS 멀티시세: REAL credential 없음 — 빈 결과 반환")
                    return emptyList()
                }
        val userId = cred.id.userId
        // P0 패턴 (2026-05-20) — DB userId 빈 문자열 방어 (Redis 토큰 cache pollution 차단)
        if (userId.isBlank()) {
            log.warn("KIS 멀티시세: credential userId 빈 문자열 — DB 결함 (cache pollution 차단)")
            return emptyList()
        }
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                log.warn("KIS 멀티시세: 토큰 발급 실패 — {}", e.message)
                return emptyList()
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val result = mutableListOf<MultiPriceQuote>()
            for (chunk in validCodes.chunked(MAX_SYMBOLS_PER_CALL)) {
                result += fetchChunk(chunk, mrktDivCode, accessToken, appKeyStr, secretKeyStr, priority)
            }
            log.info(
                "KIS 멀티시세 완료: 요청={} chunk={} 응답={} mrktDivCode={} priority={}",
                validCodes.size,
                (validCodes.size + MAX_SYMBOLS_PER_CALL - 1) / MAX_SYMBOLS_PER_CALL,
                result.size,
                mrktDivCode,
                priority,
            )
            result
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * 30종목 이하 1 chunk = 1 KIS 호출. 응답 output(배열)을 [MultiPriceQuote]로 변환.
     *
     * 결손 graceful: rt_cd != 0 또는 HTTP 오류 시 해당 chunk 빈 list (mock 0).
     *   Rate Limit(EGW00201/429)은 onTpsExceeded 시그널 + 빈 결과 (다음 chunk 진행).
     */
    private fun fetchChunk(
        chunk: List<String>,
        mrktDivCode: String,
        accessToken: String,
        appKeyStr: String,
        secretKeyStr: String,
        priority: KisCallPriority,
    ): List<MultiPriceQuote> {
        var signalSent = false
        var acquired = false
        return try {
            kisRateLimiterPort.acquire(priority, timeoutMillis = 5000L, trId = TR_ID)
            acquired = true

            @Suppress("UNCHECKED_CAST")
            val body =
                webClient
                    .get()
                    .uri { builder ->
                        val b =
                            builder
                                .scheme("https")
                                .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                .path(ENDPOINT)
                        // raw: FID_COND_MRKT_DIV_CODE_N + FID_INPUT_ISCD_N 쌍 (N=1..30, _1 필수)
                        chunk.forEachIndexed { idx, code ->
                            val n = idx + 1
                            b.queryParam("FID_COND_MRKT_DIV_CODE_$n", mrktDivCode)
                            b.queryParam("FID_INPUT_ISCD_$n", code)
                        }
                        b.build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block() as? Map<String, Any?>

            if (body == null) {
                log.warn("KIS 멀티시세 빈 body — chunk size={} mrktDivCode={}", chunk.size, mrktDivCode)
                kisRateLimiterPort.onSuccess()
                signalSent = true
                return emptyList()
            }

            val rtCd = body["rt_cd"]?.toString()
            if (rtCd != "0") {
                val msgCd = body["msg_cd"]?.toString()
                val msg = body["msg1"]?.toString()
                if (msgCd == "EGW00201" || msg?.contains("초당 거래건수") == true) {
                    kisRateLimiterPort.onTpsExceeded()
                    signalSent = true
                } else {
                    kisRateLimiterPort.onSuccess()
                    signalSent = true
                }
                log.warn(
                    "KIS 멀티시세 rt_cd != 0 — chunk size={} rt_cd={} msg_cd={} msg={}",
                    chunk.size,
                    rtCd,
                    msgCd,
                    msg,
                )
                return emptyList()
            }

            kisRateLimiterPort.onSuccess()
            signalSent = true
            parseMultiPriceResponse(body)
        } catch (e: WebClientResponseException) {
            val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
            if (errorClass is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS 멀티시세 Rate Limit — chunk size={} status={}", chunk.size, e.statusCode.value())
            } else {
                log.warn("KIS 멀티시세 HTTP 오류 — chunk size={} status={}", chunk.size, e.statusCode.value())
            }
            emptyList()
        } catch (e: Exception) {
            log.warn("KIS 멀티시세 오류 — chunk size={} err={}", chunk.size, e.message)
            emptyList()
        } finally {
            if (acquired && !signalSent) {
                log.warn("[KisRateLimit] 멀티시세 acquire 후 통계 시그널 미발행 — drift risk: chunk size={}", chunk.size)
            }
        }
    }

    /**
     * raw output 배열 → [MultiPriceQuote] list (KIS-first 필드 매핑 — COLUMN_MAPPING verbatim).
     *
     * output 키는 KIS 응답 구조상 "output" 배열. inter_shrn_iscd가 0건/공백이면 skip (데이터 없음 path).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseMultiPriceResponse(body: Map<*, *>): List<MultiPriceQuote> {
        val output = body["output"] as? List<Map<String, Any?>> ?: return emptyList()
        return output.mapNotNull { row ->
            val code = row["inter_shrn_iscd"]?.toString()?.trim()
            if (code.isNullOrBlank() || !CODE_REGEX.matches(code)) {
                return@mapNotNull null
            }
            MultiPriceQuote(
                code = code,
                open = decimalOrNull(row["inter2_oprc"]),
                high = decimalOrNull(row["inter2_hgpr"]),
                low = decimalOrNull(row["inter2_lwpr"]),
                close = decimalOrNull(row["inter2_prpr"]),
                previousClose = decimalOrNull(row["inter2_prdy_clpr"]),
                accumulatedVolume = longOrNull(row["acml_vol"]),
                accumulatedTradeAmount = decimalOrNull(row["acml_tr_pbmn"]),
                kospiKosdaqClsName = row["kospi_kosdaq_cls_name"]?.toString()?.trim()?.ifBlank { null },
                marketTreatmentClsName = row["mrkt_trtm_cls_name"]?.toString()?.trim()?.ifBlank { null },
                hourClsCode = row["hour_cls_code"]?.toString()?.trim()?.ifBlank { null },
            )
        }
    }

    private fun decimalOrNull(v: Any?): BigDecimal? =
        v
            ?.toString()
            ?.trim()
            ?.ifBlank { null }
            ?.toBigDecimalOrNull()

    private fun longOrNull(v: Any?): Long? =
        v
            ?.toString()
            ?.trim()
            ?.ifBlank { null }
            ?.toLongOrNull()
}
