package kr.spdaewoon.magicjar.api.infrastructure.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import java.net.URI

/**
 * TraceIdWebClientFilter 단위 테스트 — ADR 0084 P1-3 정합.
 *
 * 검증 case:
 * 1. MDC.traceId 존재 → outbound request header X-Trace-Id 부착
 * 2. MDC.traceId 부재 → request 그대로 통과
 * 3. 이미 X-Trace-Id 부착된 request → skip
 */
class TraceIdWebClientFilterTest {
    private val filter = TraceIdWebClientFilter()

    @AfterEach
    fun cleanup() {
        MDC.remove(TraceIdWebClientFilter.MDC_KEY_TRACE_ID)
    }

    @Test
    fun `MDC traceId 존재 시 outbound request X-Trace-Id 부착`() {
        val traceId = "trace-id-from-mdc-12345"
        MDC.put(TraceIdWebClientFilter.MDC_KEY_TRACE_ID, traceId)
        val request = ClientRequest.create(HttpMethod.GET, URI.create("https://example.com/api")).build()

        var observed: ClientRequest? = null
        val next =
            ExchangeFunction { req ->
                observed = req
                Mono.just(ClientResponse.create(HttpStatus.OK).build())
            }

        filter.filter(request, next).block()

        assertThat(observed).isNotNull
        assertThat(observed!!.headers().getFirst(TraceIdWebClientFilter.HEADER_TRACE_ID))
            .isEqualTo(traceId)
    }

    @Test
    fun `MDC traceId 부재 시 request 그대로 통과 — header 미부착`() {
        val request = ClientRequest.create(HttpMethod.GET, URI.create("https://example.com/api")).build()

        var observed: ClientRequest? = null
        val next =
            ExchangeFunction { req ->
                observed = req
                Mono.just(ClientResponse.create(HttpStatus.OK).build())
            }

        filter.filter(request, next).block()

        assertThat(observed).isNotNull
        assertThat(observed!!.headers().getFirst(TraceIdWebClientFilter.HEADER_TRACE_ID)).isNull()
    }

    @Test
    fun `이미 X-Trace-Id 부착된 request는 skip — 명시 부착 보호`() {
        val mdcTraceId = "mdc-trace"
        val explicitTraceId = "explicit-trace"
        MDC.put(TraceIdWebClientFilter.MDC_KEY_TRACE_ID, mdcTraceId)

        val request =
            ClientRequest
                .create(HttpMethod.GET, URI.create("https://example.com/api"))
                .header(TraceIdWebClientFilter.HEADER_TRACE_ID, explicitTraceId)
                .build()

        var observed: ClientRequest? = null
        val next =
            ExchangeFunction { req ->
                observed = req
                Mono.just(ClientResponse.create(HttpStatus.OK).build())
            }

        filter.filter(request, next).block()

        assertThat(observed!!.headers().getFirst(TraceIdWebClientFilter.HEADER_TRACE_ID))
            .`as`("명시 부착 헤더 보존 의무")
            .isEqualTo(explicitTraceId)
    }
}
