package kr.spdaewoon.magicjar.consumer.infrastructure.observability

import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.slf4j.MDC

/**
 * MDC traceId → Kafka header `X-Trace-Id` 자동 부착 producer interceptor (consumer 모듈)
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * api 모듈의 동일 클래스와 동일 동작 — consumer 모듈에서는 OrderRequestedKafkaProducer ·
 * OrderRejectedKafkaProducer · SignalGeneratedKafkaProducer 등 downstream 발행 시
 * 진입 listener의 MDC traceId를 그대로 새 Kafka record header로 전파.
 *
 * 헤더 chain: 진입 Kafka record `X-Trace-Id` → MDC → outbound Kafka record `X-Trace-Id`
 * → 다음 consumer MDC → ... (단일 traceId chain).
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
class TraceIdKafkaProducerInterceptor : ProducerInterceptor<Any?, Any?> {
    override fun onSend(record: ProducerRecord<Any?, Any?>): ProducerRecord<Any?, Any?> {
        val traceId = MDC.get(MDC_KEY_TRACE_ID) ?: return record
        if (record.headers().lastHeader(HEADER_TRACE_ID) != null) return record
        record.headers().add(HEADER_TRACE_ID, traceId.toByteArray(Charsets.UTF_8))
        return record
    }

    override fun onAcknowledgement(
        metadata: RecordMetadata?,
        exception: Exception?,
    ) {
        // no-op
    }

    override fun close() {
        // no-op
    }

    override fun configure(configs: MutableMap<String, *>?) {
        // no-op
    }

    companion object {
        const val MDC_KEY_TRACE_ID = "traceId"
        const val HEADER_TRACE_ID = "X-Trace-Id"
    }
}
