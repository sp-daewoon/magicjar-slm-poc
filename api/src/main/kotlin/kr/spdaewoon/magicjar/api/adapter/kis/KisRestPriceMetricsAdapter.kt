package kr.spdaewoon.magicjar.api.adapter.kis

import kr.spdaewoon.magicjar.api.infrastructure.kis.KisTokenCacheHelper
import kr.spdaewoon.magicjar.domain.market.PriceMetrics
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * KIS REST 가격·메타 조회 어댑터 (api 모듈) — SymbolMetaService 전용.
 *
 * KIS-first 출처: open-trading-api `inquire_daily_itemchartprice` (FHKST03010100).
 * endpoint: GET /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice
 *
 * 2026-05-20 endpoint 교체 사유 (사용자 dogfooding evidence — SK하이닉스 좌측 시세창 NXT 영향):
 * - 이전: `inquire-price` (FHKST01010100) `stck_prpr` → KIS 응답이 NXT 시간외 거래를 반영
 *   (SK하이닉스 stck_prpr=1,745,000 ≠ MTS 정규장 종가 1,719,000 + candle DB close_raw=1,719,000).
 * - 신규: `inquire-daily-itemchartprice` output1 → candle endpoint와 동일 source(정규장 종가) 사용.
 *   `stck_prdy_clpr`(정확한 전일 종가) 직접 제공 — 기존 `stck_sdpr`(기준가) 대체.
 *   PER·PBR·EPS·시총·종목명·업종도 output1에 포함 — single endpoint 호출.
 *
 * 응답 분리:
 * - output1: 종목 메타 + 당일 시세 (스칼라 object) — 본 어댑터 사용
 * - output2: 일봉 시계열 (배열) — candle adapter 영역, 본 어댑터 무시
 *
 * 제한: BPS는 output1에 없음 → null graceful (사용자가 필요 시 별도 endpoint 도입).
 *
 * 토큰 발급: [KisTokenCacheHelper] 위임 — `kis:token:{userId}:{accountType}` 단일 prefix LOCK (T-1).
 * timeout: 3s.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class KisRestPriceMetricsAdapter(
    private val webClient: WebClient,
    private val tokenHelper: KisTokenCacheHelper,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Suppress("UNUSED_PARAMETER")
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    kisBaseVps: String,
) : KisRestPriceMetricsPort {
    private val log = LoggerFactory.getLogger(KisRestPriceMetricsAdapter::class.java)

    override fun fetchPriceMetrics(
        userId: String,
        code: String,
    ): PriceMetrics {
        val baseUrl = kisBaseProd // 시세·캔들·메타 조회는 항상 실전 endpoint
        val (token, appKey, secretKey) = tokenHelper.resolveTokenAndKeys(userId, AccountType.REAL)

        // inquire-daily-itemchartprice 호출 파라미터:
        // - fid_cond_mrkt_div_code=J (KRX 정규장 — NXT 분리)
        // - fid_period_div_code=D (일봉 — 최신 영업일 종가 source)
        // - fid_org_adj_prc=0 (수정주가 — adjustment 적용)
        // - fid_input_date_1/_2 — 최근 7일 윈도(주말·공휴일 cover) → output1은 최신 1봉 메타
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val startDate = today.minusDays(7).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val endDate = today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

        // Rate Limit acquire (D-2 합류 — USER_PATH priority — SymbolMetaService 사용자 응답 path)
        // try 안 진입 + signalSent flag로 drift 차단 (D-1 패턴 정합).
        var signalSent = false
        return try {
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
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", "FHKST03010100")
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(3))
                    .block() as? Map<String, Any>
                    ?: error("KIS inquire-daily-itemchartprice 응답 없음 — code=$code")

            val output1 =
                response["output1"] as? Map<String, Any>
                    ?: error("KIS inquire-daily-itemchartprice output1 없음 — code=$code")

            kisRateLimiterPort.onSuccess()
            signalSent = true
            PriceMetrics(
                per = output1["per"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                pbr = output1["pbr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                eps = output1["eps"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                // BPS는 inquire-daily-itemchartprice output1에 없음 — nullable graceful (필요 시 별도 endpoint 도입)
                bps = null,
                marketCap = output1["hts_avls"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                // 업종 한글명은 output1에 없음 (industry 코드만 instrument 테이블에서 1순위 매핑) → null로 두고 SymbolMetaService fallback에 위임
                industry = null,
                name = output1["hts_kor_isnm"]?.toString()?.ifBlank { null },
                // 시장 한글명도 output1에 없음 → null로 두고 SymbolMetaService fallback에 위임
                market = null,
                currentPrice = output1["stck_prpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                // stck_prdy_clpr: 정확한 전일 종가 (기존 stck_sdpr 기준가 대체)
                previousClose = output1["stck_prdy_clpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                priceChange = output1["prdy_vrss"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                changeRate = output1["prdy_ctrt"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                changeSign = output1["prdy_vrss_sign"]?.toString()?.ifBlank { null },
                high = output1["stck_hgpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                low = output1["stck_lwpr"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                open = output1["stck_oprc"]?.toString()?.ifBlank { null }?.toBigDecimalOrNull(),
                volume = output1["acml_vol"]?.toString()?.ifBlank { null }?.toLongOrNull(),
            )
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 body 검사 포함).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.error(
                "KIS inquire-daily-itemchartprice 실패: code={} status={} body={}",
                code,
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] PriceMetrics acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={}",
                    code,
                )
            }
        }
    }
}
