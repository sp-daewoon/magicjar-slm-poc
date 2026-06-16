package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
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
import java.time.Instant

/**
 * KIS 국내 업종 등락 ranking 어댑터 — FHPUP02140000 (ADR 0100 §2-3 LOCK).
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 + ADR 0100 §3-2 #3)
 * - raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_category_price/inquire_index_category_price.py
 * - endpoint: /uapi/domestic-stock/v1/quotations/inquire-index-category-price
 * - tr_id: FHPUP02140000
 * - query params (raw 정합):
 *   · FID_COND_MRKT_DIV_CODE = "U" (업종 분류)
 *   · FID_INPUT_ISCD          = "0001" (KOSPI 상위 분류 — 21 업종 일괄 반환)
 *   · FID_COND_SCR_DIV_CODE   = "20214" (KIS 화면 분류 코드 LOCK)
 *   · FID_MRKT_CLS_CODE        = "K" (거래소 — 21 KOSPI 업종) · "Q"=KOSDAQ · "K2"=KOSPI200
 *   · FID_BLNG_CLS_CODE        = "0" (전체)
 * - 응답 (raw 정합):
 *   · output1/output2 페어 = sector 목록 + 등락률 + 거래대금
 *   · pagination: tr_cont=M(more)/F(final) 재귀 — max 10 page LOCK (KOSPI 21 업종은 1 페이지 충분)
 *
 * ### Sector 명칭 매핑 (IndexCode.KOSPI_SECTORS 카탈로그 정합)
 * KIS output에서 sector_name 필드는 직접 제공되지 않을 수 있음 → IndexCode 카탈로그로 fallback.
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - acquire(INGESTION_LOW, timeoutMillis = 3000L) 의무
 */
@Component
class KisDomesticSectorRankingAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val objectMapper: ObjectMapper,
    private val gate: KisIndexCredentials,
    @Value("\${magicjar.kis.index.sector-max-pages:10}")
    private val maxPages: Int,
) {
    private val log = LoggerFactory.getLogger(KisDomesticSectorRankingAdapter::class.java)

    companion object {
        private const val TR_ID = "FHPUP02140000"
        private const val ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-index-category-price"
        private const val FID_COND_MRKT_DIV_CODE = "U"
        private const val FID_COND_SCR_DIV_CODE = "20214"
        private const val ROOT_ISCD = "0001"
        private val SECTOR_NAME_BY_CODE: Map<String, String> = IndexCode.KOSPI_SECTORS.associate { it.code to it.displayName }
    }

    @Suppress("UNCHECKED_CAST")
    fun fetch(
        marketClsCode: String,
        belongClsCode: String,
    ): List<SectorIndexSnapshot> {
        val credential =
            gate.resolve()
                ?: run {
                    log.debug("[KisDomesticSectorRanking] credential skip")
                    return emptyList()
                }

        val accumulated = mutableListOf<SectorIndexSnapshot>()
        var trCont = ""
        var page = 0
        val now = Instant.now()

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
                                .queryParam("FID_COND_MRKT_DIV_CODE", FID_COND_MRKT_DIV_CODE)
                                .queryParam("FID_INPUT_ISCD", ROOT_ISCD)
                                .queryParam("FID_COND_SCR_DIV_CODE", FID_COND_SCR_DIV_CODE)
                                .queryParam("FID_MRKT_CLS_CODE", marketClsCode)
                                .queryParam("FID_BLNG_CLS_CODE", belongClsCode)
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

                val responseBody = response?.body as? Map<String, Any> ?: break
                accumulated += parseSectorList(responseBody, now)

                // KIS pagination tr_cont = "M"(more) / "F"(final) / "D"(default no-more)
                val nextTrCont = response.headers.getFirst("tr_cont")?.trim() ?: ""
                if (nextTrCont == "M" || nextTrCont == "F") {
                    trCont = "N" // next request indicates continuation per KIS convention
                    if (nextTrCont == "F") break // last page already consumed
                } else {
                    break // no more pages
                }
            } catch (e: WebClientResponseException) {
                val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
                if (errorClass is KisErrorClass.RateLimit) {
                    kisRateLimiterPort.onTpsExceeded()
                    signalSent = true
                }
                log.warn(
                    "[KisDomesticSectorRanking] KIS error — status={} class={} msg={}",
                    e.statusCode.value(),
                    errorClass::class.simpleName,
                    e.responseBodyAsString.take(200),
                )
                break
            } catch (e: Exception) {
                log.warn("[KisDomesticSectorRanking] 호출 실패 page={} err={}", page, e.message)
                break
            } finally {
                if (!signalSent) {
                    log.warn("[KisRateLimit] KisDomesticSectorRanking acquire 후 통계 시그널 미발행 — drift risk page={}", page)
                }
            }
        }

        // sector_code 멱등 dedup (pagination 재중복 흡수)
        return accumulated.distinctBy { it.sectorCode }
    }

    /**
     * KIS output → SectorIndexSnapshot list 변환.
     *
     * KIS output 구조 (raw 정합):
     *   output1: { ... sector list with bstp_cls_code, bstp_nmix_prpr, bstp_nmix_prdy_ctrt, acml_tr_pbmn }
     *   output2: { ... 합산 결과 (본 adapter 미사용) }
     *
     * `internal` 가시성 — 단위 테스트 검증.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseSectorList(
        response: Map<String, Any>,
        ts: Instant,
    ): List<SectorIndexSnapshot> {
        val output1 = response["output1"] as? List<Map<String, Any?>> ?: return emptyList()
        val rawJson = KisIndexCommons.serializeRawPayload(objectMapper, response, log)

        return output1.mapNotNull { item ->
            try {
                val code = item["bstp_cls_code"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val price = KisIndexCommons.bd(item["bstp_nmix_prpr"])
                if (price <= BigDecimal.ZERO) return@mapNotNull null
                val rate =
                    KisIndexCommons.bdOrNull(item["bstp_nmix_prdy_ctrt"])
                        ?: BigDecimal.ZERO
                val name = SECTOR_NAME_BY_CODE[code] ?: item["hts_kor_isnm"]?.toString() ?: code
                SectorIndexSnapshot(
                    sectorCode = code,
                    sectorName = name,
                    ts = ts,
                    price = price,
                    changeRate = rate,
                    tradeValue = KisIndexCommons.bdOrNull(item["acml_tr_pbmn"]),
                    rawPayload = rawJson,
                )
            } catch (e: Exception) {
                log.warn("[KisDomesticSectorRanking] item parse 실패 — item={} err={}", item, e.message)
                null
            }
        }
    }
}
