package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

/**
 * KIS 국내 지수·업종 현재 시세 어댑터 — FHPUP02100000 (ADR 0100 §2-3 LOCK).
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 + ADR 0100 §3-2 #1)
 * - raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_price/inquire_index_price.py
 * - endpoint: /uapi/domestic-stock/v1/quotations/inquire-index-price
 * - tr_id: FHPUP02100000
 * - query params (raw 정합):
 *   · FID_COND_MRKT_DIV_CODE = "U" (업종 분류 — KIS spec 명시 LOCK)
 *   · FID_INPUT_ISCD          = IndexCode.code raw (예: "0001"=KOSPI · "1001"=KOSDAQ · "2001"=KOSPI200 · KOSPI 21 업종 0002~0022)
 * - 응답 output (KIS 공식 doc portal 정합):
 *   · bstp_nmix_prpr       = 업종 지수 현재가 (price 매핑)
 *   · bstp_nmix_prdy_vrss  = 전일 대비 (사용 0 — 영속 컬럼 없음)
 *   · bstp_nmix_prdy_ctrt  = 전일 대비율 % (changeRate 매핑)
 *   · acml_vol             = 누적 거래량 (volume 매핑 — 일부 업종 미제공 nullable)
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - acquire(INGESTION_LOW, timeoutMillis = 3000L) 의무
 * - signalSent flag drift 차단 — ApiKisBalanceQueryAdapter 패턴 1:1 reuse
 *
 * ### 자본 영향 0 (ADR 0100 §4-1·§4-2)
 * - read-only KIS 호출 + market_index_snapshot 영속만. 주문·Risk·Outbox 0 touch.
 */
@Component
class KisDomesticIndexAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val objectMapper: ObjectMapper,
    private val gate: KisIndexCredentials,
) {
    private val log = LoggerFactory.getLogger(KisDomesticIndexAdapter::class.java)

    companion object {
        private const val TR_ID = "FHPUP02100000"
        private const val ENDPOINT = "/uapi/domestic-stock/v1/quotations/inquire-index-price"
        private const val FID_COND_MRKT_DIV_CODE = "U"
    }

    /**
     * 국내 지수·업종 1건 fetch.
     *
     * @return null 시: disabled gate / KIS 응답 부재 / RateLimit drop — caller batch Job 자연 skip
     */
    fun fetch(code: IndexCode): MarketIndexSnapshot? {
        val credential =
            gate.resolve()
                ?: run {
                    log.debug("[KisDomesticIndex] credential skip — code={} (static env/DB REAL credential 미설정)", code.code)
                    return null
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
                            .queryParam("FID_COND_MRKT_DIV_CODE", FID_COND_MRKT_DIV_CODE)
                            .queryParam("FID_INPUT_ISCD", code.code)
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
                log.warn("[KisDomesticIndex] 응답 null — code={}", code.code)
                kisRateLimiterPort.onSuccess()
                signalSent = true
                return null
            }
            kisRateLimiterPort.onSuccess()
            signalSent = true

            parseSnapshot(code, response)
        } catch (e: WebClientResponseException) {
            val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
            if (errorClass is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn(
                "[KisDomesticIndex] KIS error — code={} status={} class={} msg={}",
                code.code,
                e.statusCode.value(),
                errorClass::class.simpleName,
                e.responseBodyAsString.take(200),
            )
            null
        } catch (e: Exception) {
            log.warn("[KisDomesticIndex] 호출 실패 — code={} err={}", code.code, e.message)
            null
        } finally {
            if (!signalSent) {
                log.warn("[KisRateLimit] KisDomesticIndex acquire 후 통계 시그널 미발행 — drift risk code={}", code.code)
            }
        }
    }

    /**
     * 응답 → MarketIndexSnapshot 변환.
     *
     * KIS 응답 구조 (raw 출처 인용 정합):
     *   { "rt_cd": "0", "msg_cd": "...", "output": { "bstp_nmix_prpr": "...", "bstp_nmix_prdy_ctrt": "...", "acml_vol": "..." } }
     *
     * `internal` 가시성 — 단위 테스트 검증 목적 (헌법 §6 함정 무관).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseSnapshot(
        code: IndexCode,
        response: Map<String, Any>,
    ): MarketIndexSnapshot? {
        val output =
            when (val raw = response["output"]) {
                is Map<*, *> -> raw as Map<String, Any?>
                is List<*> -> (raw.firstOrNull() as? Map<String, Any?>)
                else -> null
            } ?: run {
                log.warn("[KisDomesticIndex] output 부재 — code={} rt_cd={}", code.code, response["rt_cd"])
                return null
            }

        val price = KisIndexCommons.bd(output["bstp_nmix_prpr"])
        if (price <= java.math.BigDecimal.ZERO) {
            log.warn("[KisDomesticIndex] price 0/음수 — code={} 영속 skip", code.code)
            return null
        }

        return MarketIndexSnapshot(
            code = code.code,
            category = code.category,
            ts = Instant.now(),
            price = price,
            changeRate = KisIndexCommons.bdOrNull(output["bstp_nmix_prdy_ctrt"]),
            volume = KisIndexCommons.longOrNull(output["acml_vol"]),
            rawPayload = KisIndexCommons.serializeRawPayload(objectMapper, response, log),
        )
    }
}
