package kr.spdaewoon.magicjar.api.infrastructure.observability

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * TraceIdKafkaProducerInterceptor 단위 테스트 — ADR 0084 P1-3 정합.
 *
 * 검증 case:
 * 1. MDC.traceId 존재 → record header `X-Trace-Id` 부착
 * 2. MDC.traceId 부재 → record header 미부착 (record 그대로 반환)
 * 3. 이미 header 부착된 record → skip (외부 system 부착 케이스 보호)
 */
class TraceIdKafkaProducerInterceptorTest {
    private val interceptor = TraceIdKafkaProducerInterceptor()

    @AfterEach
    fun cleanup() {
        MDC.remove(TraceIdKafkaProducerInterceptor.MDC_KEY_TRACE_ID)
    }

    @Test
    fun `MDC traceId 존재 시 record header X-Trace-Id 부착`() {
        val traceId = "abc123def456abc123def456abc123de"
        MDC.put(TraceIdKafkaProducerInterceptor.MDC_KEY_TRACE_ID, traceId)
        val record = ProducerRecord<Any?, Any?>(KafkaTopics.Order.INTENT, "user-1", "{}")

        val result = interceptor.onSend(record)

        val header = result.headers().lastHeader(TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID)
        assertThat(header?.value()?.let { String(it, Charsets.UTF_8) }).isEqualTo(traceId)
    }

    @Test
    fun `MDC traceId 부재 시 record header 미부착`() {
        // MDC clear 상태
        val record = ProducerRecord<Any?, Any?>(KafkaTopics.Order.INTENT, "user-1", "{}")

        val result = interceptor.onSend(record)

        assertThat(result.headers().lastHeader(TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID)).isNull()
    }

    @Test
    fun `이미 X-Trace-Id 부착된 record는 skip — 외부 부착 보호`() {
        val mdcTraceId = "mdc-trace-1234"
        val existingTraceId = "existing-trace-7890"
        MDC.put(TraceIdKafkaProducerInterceptor.MDC_KEY_TRACE_ID, mdcTraceId)
        val record = ProducerRecord<Any?, Any?>(KafkaTopics.Order.INTENT, "user-1", "{}")
        record.headers().add(
            TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID,
            existingTraceId.toByteArray(Charsets.UTF_8),
        )

        val result = interceptor.onSend(record)

        val header = result.headers().lastHeader(TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID)
        assertThat(header?.value()?.let { String(it, Charsets.UTF_8) })
            .`as`("기존 헤더 보존 의무")
            .isEqualTo(existingTraceId)
    }
}
