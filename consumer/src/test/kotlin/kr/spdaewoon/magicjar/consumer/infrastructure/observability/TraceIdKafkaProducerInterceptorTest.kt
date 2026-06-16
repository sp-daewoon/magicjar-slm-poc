package kr.spdaewoon.magicjar.consumer.infrastructure.observability

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * TraceIdKafkaProducerInterceptor 단위 테스트 (consumer 모듈) — ADR 0084 P1-3 정합.
 *
 * api 모듈 동일 클래스와 동작 동등 — chain (record header → MDC → outbound header) 보장.
 */
class TraceIdKafkaProducerInterceptorTest {
    private val interceptor = TraceIdKafkaProducerInterceptor()

    @AfterEach
    fun cleanup() {
        MDC.remove(TraceIdKafkaProducerInterceptor.MDC_KEY_TRACE_ID)
    }

    @Test
    fun `MDC traceId 존재 시 record header X-Trace-Id 부착`() {
        val traceId = "consumer-downstream-trace"
        MDC.put(TraceIdKafkaProducerInterceptor.MDC_KEY_TRACE_ID, traceId)
        val record = ProducerRecord<Any?, Any?>(KafkaTopics.Order.REQUESTED, "user-1", "{}")

        val result = interceptor.onSend(record)

        val header = result.headers().lastHeader(TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID)
        assertThat(header?.value()?.let { String(it, Charsets.UTF_8) }).isEqualTo(traceId)
    }

    @Test
    fun `MDC traceId 부재 시 record header 미부착`() {
        val record = ProducerRecord<Any?, Any?>(KafkaTopics.Order.REQUESTED, "user-1", "{}")

        val result = interceptor.onSend(record)

        assertThat(result.headers().lastHeader(TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID)).isNull()
    }

    @Test
    fun `이미 X-Trace-Id 부착된 record는 skip`() {
        val mdcTraceId = "mdc"
        val existing = "existing"
        MDC.put(TraceIdKafkaProducerInterceptor.MDC_KEY_TRACE_ID, mdcTraceId)
        val record = ProducerRecord<Any?, Any?>(KafkaTopics.Order.REQUESTED, "user-1", "{}")
        record.headers().add(
            TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID,
            existing.toByteArray(Charsets.UTF_8),
        )

        val result = interceptor.onSend(record)

        val header = result.headers().lastHeader(TraceIdKafkaProducerInterceptor.HEADER_TRACE_ID)
        assertThat(header?.value()?.let { String(it, Charsets.UTF_8) }).isEqualTo(existing)
    }
}
