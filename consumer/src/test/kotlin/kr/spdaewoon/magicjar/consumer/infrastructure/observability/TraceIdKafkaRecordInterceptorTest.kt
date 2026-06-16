package kr.spdaewoon.magicjar.consumer.infrastructure.observability

import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * TraceIdKafkaRecordInterceptor 단위 테스트 — ADR 0084 P1-3 정합.
 *
 * 검증 case:
 * 1. inbound record header `X-Trace-Id` 존재 → MDC 복원 (chain 유지)
 * 2. inbound header 부재 → UUID 신규 생성 (32-hex)
 * 3. success() 후 MDC clear
 * 4. failure() 후 MDC clear
 */
class TraceIdKafkaRecordInterceptorTest {
    private val interceptor = TraceIdKafkaRecordInterceptor()
    private val consumer = mockk<org.apache.kafka.clients.consumer.Consumer<Any, Any>>(relaxed = true)

    @AfterEach
    fun cleanup() {
        MDC.remove(TraceIdKafkaRecordInterceptor.MDC_KEY_TRACE_ID)
    }

    @Test
    fun `inbound record header X-Trace-Id 존재 시 MDC 복원`() {
        val traceId = "incoming-trace-id-from-producer"
        val record = ConsumerRecord<Any, Any>(KafkaTopics.Order.INTENT, 0, 0L, "user-1", "{}")
        record.headers().add(
            TraceIdKafkaRecordInterceptor.HEADER_TRACE_ID,
            traceId.toByteArray(Charsets.UTF_8),
        )

        interceptor.intercept(record, consumer)

        assertThat(MDC.get(TraceIdKafkaRecordInterceptor.MDC_KEY_TRACE_ID)).isEqualTo(traceId)
    }

    @Test
    fun `inbound header 부재 시 UUID 신규 생성 — 32-hex`() {
        val record = ConsumerRecord<Any, Any>(KafkaTopics.Order.INTENT, 0, 0L, "user-1", "{}")

        interceptor.intercept(record, consumer)

        val mdcTraceId = MDC.get(TraceIdKafkaRecordInterceptor.MDC_KEY_TRACE_ID)
        assertThat(mdcTraceId).`as`("header 부재 시 신규 traceId 생성 의무").isNotNull()
        assertThat(mdcTraceId!!.length).`as`("UUID 32-hex 형식").isEqualTo(32)
        assertThat(mdcTraceId.matches(Regex("[0-9a-f]+"))).`as`("hex 문자만 포함").isTrue()
    }

    @Test
    fun `success 호출 후 MDC clear`() {
        val record = ConsumerRecord<Any, Any>(KafkaTopics.Order.INTENT, 0, 0L, "user-1", "{}")
        interceptor.intercept(record, consumer)

        interceptor.success(record, consumer)

        assertThat(MDC.get(TraceIdKafkaRecordInterceptor.MDC_KEY_TRACE_ID))
            .`as`("listener 성공 후 MDC clear 의무")
            .isNull()
    }

    @Test
    fun `failure 호출 후 MDC clear`() {
        val record = ConsumerRecord<Any, Any>(KafkaTopics.Order.INTENT, 0, 0L, "user-1", "{}")
        interceptor.intercept(record, consumer)

        interceptor.failure(record, RuntimeException("listener 실패"), consumer)

        assertThat(MDC.get(TraceIdKafkaRecordInterceptor.MDC_KEY_TRACE_ID))
            .`as`("listener 실패 후에도 MDC clear 의무")
            .isNull()
    }

    @Test
    fun `빈 header 값 시 신규 생성`() {
        val record = ConsumerRecord<Any, Any>(KafkaTopics.Order.INTENT, 0, 0L, "user-1", "{}")
        record.headers().add(
            TraceIdKafkaRecordInterceptor.HEADER_TRACE_ID,
            "".toByteArray(Charsets.UTF_8),
        )

        interceptor.intercept(record, consumer)

        val mdcTraceId = MDC.get(TraceIdKafkaRecordInterceptor.MDC_KEY_TRACE_ID)
        assertThat(mdcTraceId).isNotNull()
        assertThat(mdcTraceId!!.length).`as`("빈 header 값은 신규 생성").isEqualTo(32)
    }
}
