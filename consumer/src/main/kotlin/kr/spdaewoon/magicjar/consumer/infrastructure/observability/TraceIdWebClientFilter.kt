package kr.spdaewoon.magicjar.consumer.infrastructure.observability

import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono

/**
 * MDC traceId → 외부 HTTP 요청 `X-Trace-Id` 자동 부착 WebClient filter (consumer 모듈)
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * api 모듈의 동일 클래스와 동일 동작 — consumer에서는 KIS REST 호출 (KisOrderHttpAdapter ·
 * KisAuthAdapter · KisQuoteHttpAdapter · KisReconciliationAdapter 등) 시 진입 Kafka record의
 * MDC traceId를 그대로 outbound HTTP 헤더로 전파.
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
@Component
class TraceIdWebClientFilter : ExchangeFilterFunction {
    override fun filter(
        request: ClientRequest,
        next: ExchangeFunction,
    ): Mono<org.springframework.web.reactive.function.client.ClientResponse> {
        val traceId = MDC.get(MDC_KEY_TRACE_ID)
        if (traceId.isNullOrBlank()) return next.exchange(request)
        if (request.headers().getFirst(HEADER_TRACE_ID) != null) return next.exchange(request)

        val mutated =
            ClientRequest
                .from(request)
                .header(HEADER_TRACE_ID, traceId)
                .build()
        return next.exchange(mutated)
    }

    companion object {
        const val MDC_KEY_TRACE_ID = "traceId"
        const val HEADER_TRACE_ID = "X-Trace-Id"
    }
}
