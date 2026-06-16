package kr.spdaewoon.magicjar.api.infrastructure.observability

import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono

/**
 * MDC traceId → 외부 HTTP 요청 `X-Trace-Id` 자동 부착 WebClient filter
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * ### 동작
 * `WebClient.builder().filter(this)` 등록 시 모든 outbound HTTP 호출의 헤더에
 * 현재 thread MDC `traceId` 자동 부착 → KIS·DART·Naver 등 외부 API 호출 audit log에서
 * 같은 traceId chain 추적 가능.
 *
 * ### 외부 reply
 * KIS·DART 등은 본 헤더를 처리하지 않음 — magicJar 내부 audit·gateway log에서만 의미.
 * 향후 KIS API gateway·내부 reverse proxy·log 수집기에서 활용 가능.
 *
 * ### 헤더 중복 방지
 * 이미 `X-Trace-Id` 헤더가 부착된 ClientRequest는 skip — caller 명시 부착 케이스 보호.
 *
 * ### thread 전환 안전성
 * Reactor scheduler가 다른 thread로 전환할 수 있으나, 본 filter는 ClientRequest builder
 * (caller thread 동기) 시점에 MDC를 읽으므로 안전. async 시점에는 MDC 미보장 — 별도 처리 (W5 Reactor Context).
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
