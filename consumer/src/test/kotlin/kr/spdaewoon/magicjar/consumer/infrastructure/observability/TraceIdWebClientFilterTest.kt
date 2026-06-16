package kr.spdaewoon.magicjar.consumer.infrastructure.observability

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
 * TraceIdWebClientFilter 단위 테스트 (consumer 모듈) — ADR 0084 P1-3 정합.
 *
 * consumer에서는 KIS REST 호출 path가 본 filter 거침 — 진입 Kafka record traceId가
 * outbound HTTP header `X-Trace-Id`로 chain.
 */
class TraceIdWebClientFilterTest {
    private val filter = TraceIdWebClientFilter()

    @AfterEach
    fun cleanup() {
        MDC.remove(TraceIdWebClientFilter.MDC_KEY_TRACE_ID)
    }

    @Test
    fun `MDC traceId 존재 시 outbound request X-Trace-Id 부착`() {
        val traceId = "kafka-record-trace-propagated"
        MDC.put(TraceIdWebClientFilter.MDC_KEY_TRACE_ID, traceId)
        val request = ClientRequest.create(HttpMethod.POST, URI.create("https://openapi.koreainvestment.com/uapi")).build()

        var observed: ClientRequest? = null
        val next =
            ExchangeFunction { req ->
                observed = req
                Mono.just(ClientResponse.create(HttpStatus.OK).build())
            }

        filter.filter(request, next).block()

        assertThat(observed).isNotNull
        assertThat(observed!!.headers().getFirst(TraceIdWebClientFilter.HEADER_TRACE_ID)).isEqualTo(traceId)
    }

    @Test
    fun `MDC traceId 부재 시 header 미부착`() {
        val request = ClientRequest.create(HttpMethod.GET, URI.create("https://openapi.koreainvestment.com/uapi")).build()

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
}
