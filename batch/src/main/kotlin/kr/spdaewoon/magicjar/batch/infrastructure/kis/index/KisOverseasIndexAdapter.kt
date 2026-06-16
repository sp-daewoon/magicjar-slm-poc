package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.index.IndexCategory
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
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS 해외 지수 현재가 어댑터 — HHDFS00000300 (ADR 0100 §2-3 LOCK).
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 + ADR 0100 §3-2 #4)
 * - raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/price/price.py
 * - endpoint: /uapi/overseas-price/v1/quotations/price
 * - tr_id: HHDFS00000300
 * - query params (raw 정합):
 *   · AUTH = "" (개인 클라이언트는 빈문자열 — KIS spec)
 *   · EXCD = IndexCode.exchange (예: "NAS" — NASDAQ)
 *   · SYMB = IndexCode.code (예: ".IXIC" · ".SPX" — 점 prefix가 index 표시)
 * - 응답 output (KIS doc portal 정합):
 *   · last  = 현재가 (price 매핑)
 *   · rate  = 등락률 % (changeRate 매핑)
 *   · tvol  = 거래량 (volume 매핑)
 *
 * ### exchange code 매트릭스 (ADR 0100 §3-4 LOCK)
 * 1차는 NAS(NASDAQ) 단일 — .IXIC(NASDAQ Composite) + .SPX(S&P 500) 양쪽 NAS 정합.
 * 향후 NYS(NYSE) 다우 .DJI 확장 시 IndexCode 카탈로그 추가 + ADR amend.
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24)
 * - acquire(INGESTION_LOW, timeoutMillis = 3000L) 의무
 */
@Component
class KisOverseasIndexAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val objectMapper: ObjectMapper,
    private val gate: KisIndexCredentials,
) {
    private val log = LoggerFactory.getLogger(KisOverseasIndexAdapter::class.java)

    companion object {
        private const val TR_ID = "HHDFS00000300"
        private const val ENDPOINT = "/uapi/overseas-price/v1/quotations/price"
    }

    fun fetch(code: IndexCode): MarketIndexSnapshot? {
        val credential =
            gate.resolve()
                ?: run {
                    log.debug("[KisOverseasIndex] credential skip — code={}", code.code)
                    return null
                }
        require(code.category == IndexCategory.OVERSEAS_INDEX) {
            "KisOverseasIndexAdapter는 OVERSEAS_INDEX 한정 — code=${code.code}"
        }
        val excd = requireNotNull(code.exchange) { "OVERSEAS_INDEX는 exchange 필수 — code=${code.code}" }

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
                            .queryParam("AUTH", "")
                            .queryParam("EXCD", excd)
                            .queryParam("SYMB", code.code)
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
                log.warn("[KisOverseasIndex] 응답 null — code={}", code.code)
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
                "[KisOverseasIndex] KIS error — code={} status={} class={} msg={}",
                code.code,
                e.statusCode.value(),
                errorClass::class.simpleName,
                e.responseBodyAsString.take(200),
            )
            null
        } catch (e: Exception) {
            log.warn("[KisOverseasIndex] 호출 실패 — code={} err={}", code.code, e.message)
            null
        } finally {
            if (!signalSent) {
                log.warn("[KisRateLimit] KisOverseasIndex acquire 후 통계 시그널 미발행 — drift risk code={}", code.code)
            }
        }
    }

    /**
     * KIS overseas-price output → MarketIndexSnapshot 변환.
     *
     * 응답 구조 (raw 정합):
     *   { "rt_cd": "0", ..., "output": { "last": "...", "rate": "...", "tvol": "..." } }
     *
     * `internal` 가시성 — 단위 테스트 검증.
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
                log.warn("[KisOverseasIndex] output 부재 — code={} rt_cd={}", code.code, response["rt_cd"])
                return null
            }

        val price = KisIndexCommons.bd(output["last"])
        if (price <= BigDecimal.ZERO) {
            log.warn("[KisOverseasIndex] price 0/음수 — code={} 영속 skip", code.code)
            return null
        }

        return MarketIndexSnapshot(
            code = code.code,
            category = IndexCategory.OVERSEAS_INDEX,
            ts = Instant.now(),
            price = price,
            changeRate = KisIndexCommons.bdOrNull(output["rate"]),
            volume = KisIndexCommons.longOrNull(output["tvol"]),
            rawPayload = KisIndexCommons.serializeRawPayload(objectMapper, response, log),
        )
    }
}
