package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KIS 국내 지수·업종 일봉 어댑터 — FHPUP02120000 (ADR 0100 §2-3 LOCK).
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 + ADR 0100 §3-2 #2)
 * - raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_daily_price/inquire_index_daily_price.py
 * - endpoint: /uapi/domestic-stock/v1/quotations/inquire-index-daily-price
 * - tr_id: FHPUP02120000
 * - query params (raw 정합):
 *   · FID_PERIOD_DIV_CODE     = "D"/"W"/"M" (일·주·월 — caller 지정)
 *   · FID_COND_MRKT_DIV_CODE  = "U" (업종 분류)
 *   · FID_INPUT_ISCD          = IndexCode.code raw
 *   · FID_INPUT_DATE_1        = baseDate (YYYYMMDD)
 * - 응답 output (KIS doc portal 정합):
 *   · output1 = metadata (현재가 등 — 본 adapter 사용 0)
 *   · output2 = daily candles array (각 item: stck_bsop_date·bstp_nmix_oprc·hgpr·lwpr·prpr·acml_vol)
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - acquire(INGESTION_LOW, timeoutMillis = 3000L) 의무
 */
@Component
class KisDomesticIndexDailyAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val objectMapper: ObjectMapper,
    private val gate: KisIndexCredentials,
) {
    private val log = LoggerFactory.getLogger(KisDomesticIndexDailyAdapter::class.java)

    companion object {
        private const val TR_ID = "FHPUP02120000"
        private const val ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-index-daily-price"
        private const val FID_COND_MRKT_DIV_CODE = "U"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    fun fetch(
        code: IndexCode,
        baseDate: LocalDate,
        periodDiv: String,
    ): List<MarketIndexDaily> {
        val credential =
            gate.resolve()
                ?: run {
                    log.debug("[KisDomesticIndexDaily] credential skip — code={}", code.code)
                    return emptyList()
                }

        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.INGESTION_LOW, timeoutMillis = 3000L, trId = TR_ID)

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(KisIndexCommons.parseHost(credential.baseUrl))
                            .port(KisIndexCommons.parsePort(credential.baseUrl))
                            .path(ENDPOINT)
                            .queryParam("FID_PERIOD_DIV_CODE", periodDiv)
                            .queryParam("FID_COND_MRKT_DIV_CODE", FID_COND_MRKT_DIV_CODE)
                            .queryParam("FID_INPUT_ISCD", code.code)
                            .queryParam("FID_INPUT_DATE_1", baseDate.format(DATE_FMT))
                            .build()
                    }.header("authorization", "Bearer ${credential.accessToken}")
                    .header("appkey", credential.appKey)
                    .header("appsecret", credential.appSecret)
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(KisIndexCommons.TIMEOUT)
                    .block() as? Map<String, Any>

            if (response == null) {
                log.warn("[KisDomesticIndexDaily] 응답 null — code={}", code.code)
                kisRateLimiterPort.onSuccess()
                signalSent = true
                return emptyList()
            }
            kisRateLimiterPort.onSuccess()
            signalSent = true

            parseDaily(code, response)
        } catch (e: WebClientResponseException) {
            val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
            if (errorClass is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn(
                "[KisDomesticIndexDaily] KIS error — code={} status={} class={} msg={}",
                code.code,
                e.statusCode.value(),
                errorClass::class.simpleName,
                e.responseBodyAsString.take(200),
            )
            emptyList()
        } catch (e: Exception) {
            log.warn("[KisDomesticIndexDaily] 호출 실패 — code={} err={}", code.code, e.message)
            emptyList()
        } finally {
            if (!signalSent) {
                log.warn("[KisRateLimit] KisDomesticIndexDaily acquire 후 통계 시그널 미발행 — drift risk code={}", code.code)
            }
        }
    }

    /**
     * output2 array → MarketIndexDaily list 변환.
     *
     * `internal` 가시성 — 단위 테스트 검증 목적.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseDaily(
        code: IndexCode,
        response: Map<String, Any>,
    ): List<MarketIndexDaily> {
        val output2 = response["output2"] as? List<Map<String, Any?>> ?: return emptyList()
        val rawJson = KisIndexCommons.serializeRawPayload(objectMapper, response, log)

        return output2.mapNotNull { item ->
            try {
                val dateStr = item["stck_bsop_date"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val tradeDate = LocalDate.parse(dateStr, DATE_FMT)
                val close = KisIndexCommons.bd(item["bstp_nmix_prpr"])
                if (close <= BigDecimal.ZERO) return@mapNotNull null
                MarketIndexDaily(
                    code = code.code,
                    category = code.category,
                    tradeDate = tradeDate,
                    open = KisIndexCommons.bd(item["bstp_nmix_oprc"]),
                    high = KisIndexCommons.bd(item["bstp_nmix_hgpr"]),
                    low = KisIndexCommons.bd(item["bstp_nmix_lwpr"]),
                    close = close,
                    volume = KisIndexCommons.longOrNull(item["acml_vol"]),
                    rawPayload = rawJson,
                )
            } catch (e: Exception) {
                log.warn("[KisDomesticIndexDaily] item parse 실패 — code={} item={} err={}", code.code, item, e.message)
                null
            }
        }
    }
}
