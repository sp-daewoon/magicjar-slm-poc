package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCategory
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KIS 해외 지수 일봉 어댑터 — HHDFS76240000 (ADR 0100 §2-3 LOCK).
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 + ADR 0100 §3-2 #5)
 * - raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/dailyprice/dailyprice.py
 * - endpoint: /uapi/overseas-price/v1/quotations/dailyprice
 * - tr_id: HHDFS76240000
 * - query params (raw 정합):
 *   · AUTH = "" (개인 클라이언트)
 *   · EXCD = IndexCode.exchange (예: "NAS")
 *   · SYMB = IndexCode.code (예: ".IXIC")
 *   · GUBN = "0" (daily — 일·주·월 분기. 본 adapter는 daily 단일 LOCK)
 *   · BYMD = baseDate (YYYYMMDD)
 *   · MODP = "0"(원본)·"1"(수정주가) — caller 지정 default "1"
 * - 응답 output (KIS doc portal 정합):
 *   · output1 = metadata
 *   · output2 = daily candles array (각 item: xymd·open·high·low·clos·tvol·t_xprc)
 * - pagination: tr_cont=M/F 재귀 — max 10 page LOCK (env `magicjar.kis.index.overseas-daily-max-pages` 조정 가능)
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - acquire(INGESTION_LOW, timeoutMillis = 3000L) 의무
 */
@Component
class KisOverseasIndexDailyAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val objectMapper: ObjectMapper,
    private val gate: KisIndexCredentials,
    @Value("\${magicjar.kis.index.overseas-daily-max-pages:10}")
    private val maxPages: Int,
) {
    private val log = LoggerFactory.getLogger(KisOverseasIndexDailyAdapter::class.java)

    companion object {
        private const val TR_ID = "HHDFS76240000"
        private const val ENDPOINT = "/uapi/overseas-price/v1/quotations/dailyprice"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    @Suppress("UNCHECKED_CAST")
    fun fetch(
        code: IndexCode,
        baseDate: LocalDate,
        modp: String,
    ): List<MarketIndexDaily> {
        val credential =
            gate.resolve()
                ?: run {
                    log.debug("[KisOverseasIndexDaily] credential skip — code={}", code.code)
                    return emptyList()
                }
        require(code.category == IndexCategory.OVERSEAS_INDEX) {
            "KisOverseasIndexDailyAdapter는 OVERSEAS_INDEX 한정 — code=${code.code}"
        }
        val excd = requireNotNull(code.exchange) { "OVERSEAS_INDEX는 exchange 필수" }

        val accumulated = mutableListOf<MarketIndexDaily>()
        var trCont = ""
        var page = 0
        var currentBaseDate = baseDate

        while (page < maxPages) {
            page++
            var signalSent = false
            try {
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
                                .queryParam("AUTH", "")
                                .queryParam("EXCD", excd)
                                .queryParam("SYMB", code.code)
                                .queryParam("GUBN", "0")
                                .queryParam("BYMD", currentBaseDate.format(DATE_FMT))
                                .queryParam("MODP", modp)
                                .build()
                        }.header("authorization", "Bearer ${credential.accessToken}")
                        .header("appkey", credential.appKey)
                        .header("appsecret", credential.appSecret)
                        .header("tr_id", TR_ID)
                        .header("custtype", "P")
                        .header("tr_cont", trCont)
                        .retrieve()
                        .toEntity(Map::class.java)
                        .timeout(KisIndexCommons.TIMEOUT)
                        .block()
                kisRateLimiterPort.onSuccess()
                signalSent = true

                val body = response?.body as? Map<String, Any> ?: break
                val daily = parseDaily(code, body)
                accumulated += daily

                val nextTrCont = response.headers.getFirst("tr_cont")?.trim() ?: ""
                if (nextTrCont == "M" || nextTrCont == "F") {
                    trCont = "N"
                    // 다음 페이지를 위해 마지막 일봉 날짜 -1일로 BYMD 진전
                    daily.minOfOrNull { it.tradeDate }?.let { currentBaseDate = it.minusDays(1) }
                    if (nextTrCont == "F") break
                } else {
                    break
                }
            } catch (e: WebClientResponseException) {
                val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
                if (errorClass is KisErrorClass.RateLimit) {
                    kisRateLimiterPort.onTpsExceeded()
                    signalSent = true
                }
                log.warn(
                    "[KisOverseasIndexDaily] KIS error — code={} status={} class={} msg={}",
                    code.code,
                    e.statusCode.value(),
                    errorClass::class.simpleName,
                    e.responseBodyAsString.take(200),
                )
                break
            } catch (e: Exception) {
                log.warn("[KisOverseasIndexDaily] 호출 실패 code={} page={} err={}", code.code, page, e.message)
                break
            } finally {
                if (!signalSent) {
                    log.warn("[KisRateLimit] KisOverseasIndexDaily acquire 후 통계 시그널 미발행 — drift risk code={} page={}", code.code, page)
                }
            }
        }

        return accumulated.distinctBy { it.tradeDate }
    }

    /**
     * output2 array → MarketIndexDaily list 변환.
     *
     * KIS overseas daily output 구조 (raw 정합):
     *   output1: { ... metadata }
     *   output2: [ { "xymd": "20260523", "open": "...", "high": "...", "low": "...", "clos": "...", "tvol": "..." }, ... ]
     *
     * `internal` 가시성 — 단위 테스트 검증.
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
                val dateStr = item["xymd"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val tradeDate = LocalDate.parse(dateStr, DATE_FMT)
                val close = KisIndexCommons.bd(item["clos"])
                if (close <= BigDecimal.ZERO) return@mapNotNull null
                MarketIndexDaily(
                    code = code.code,
                    category = IndexCategory.OVERSEAS_INDEX,
                    tradeDate = tradeDate,
                    open = KisIndexCommons.bd(item["open"]),
                    high = KisIndexCommons.bd(item["high"]),
                    low = KisIndexCommons.bd(item["low"]),
                    close = close,
                    volume = KisIndexCommons.longOrNull(item["tvol"]),
                    rawPayload = rawJson,
                )
            } catch (e: Exception) {
                log.warn("[KisOverseasIndexDaily] item parse 실패 — code={} item={} err={}", code.code, item, e.message)
                null
            }
        }
    }
}
