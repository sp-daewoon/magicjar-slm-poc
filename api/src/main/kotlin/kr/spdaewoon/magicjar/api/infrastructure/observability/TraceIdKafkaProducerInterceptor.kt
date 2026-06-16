package kr.spdaewoon.magicjar.api.infrastructure.observability

import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.MDC

/**
 * MDC traceId → Kafka header `X-Trace-Id` 자동 부착 producer interceptor
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * ### 동작
 * Kafka producer가 record send 직전 [onSend] 호출 → 현재 thread MDC `traceId` 추출 →
 * `ProducerRecord.headers().add("X-Trace-Id", id)` 자동 부착.
 *
 * 같은 thread에서 진행되는 HTTP request (TraceIdServletFilter가 MDC 부착) →
 * Kafka send (본 interceptor가 header 부착) → consumer (TraceIdKafkaRecordInterceptor가 MDC 복원)
 * 흐름의 단일 ID 보장.
 *
 * ### 헤더 중복 방지
 * 기존 record header에 `X-Trace-Id` 이미 있으면 skip (외부 system 또는 명시 부착 케이스 보호).
 *
 * ### 등록 방법
 * `KafkaTemplate`이 사용하는 `ProducerFactory`에 `interceptor.classes` 프로퍼티로 추가.
 * `TraceIdKafkaPropertiesCustomizer` (별도 클래스)가 Spring Boot Kafka auto-config 단계에서 자동 등록.
 *
 * ### scope
 * - api 모듈: `OrderIntentKafkaProducer` 등 자동 path 포함 모든 KafkaTemplate.send 호출
 * - 본 클래스는 `ProducerInterceptor` 표준 인터페이스 구현 — Spring Kafka 외부 (Apache Kafka 표준)
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
class TraceIdKafkaProducerInterceptor : ProducerInterceptor<Any?, Any?> {
    override fun onSend(record: ProducerRecord<Any?, Any?>): ProducerRecord<Any?, Any?> {
        val traceId = MDC.get(MDC_KEY_TRACE_ID) ?: return record
        // 외부 system 또는 명시 부착 케이스 보호 — 기존 헤더 존중
        if (record.headers().lastHeader(HEADER_TRACE_ID) != null) return record
        record.headers().add(HEADER_TRACE_ID, traceId.toByteArray(Charsets.UTF_8))
        return record
    }

    override fun onAcknowledgement(
        metadata: RecordMetadata?,
        exception: Exception?,
    ) {
        // no-op — Kafka ack 단계는 trace 부착 대상 아님
    }

    override fun close() {
        // no-op — stateless
    }

    override fun configure(configs: MutableMap<String, *>?) {
        // no-op — stateless
    }

    companion object {
        /** MDC key — TraceIdServletFilter 정합. */
        const val MDC_KEY_TRACE_ID = "traceId"

        /** Kafka header key — consumer TraceIdKafkaRecordInterceptor 정합. */
        const val HEADER_TRACE_ID = "X-Trace-Id"
    }
}
