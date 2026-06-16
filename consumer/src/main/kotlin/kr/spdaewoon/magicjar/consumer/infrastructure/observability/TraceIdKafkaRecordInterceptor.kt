package kr.spdaewoon.magicjar.consumer.infrastructure.observability

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC
import org.springframework.kafka.listener.RecordInterceptor
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Kafka record `X-Trace-Id` header → MDC `traceId` 복원 record interceptor
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * ### 동작
 * Spring Kafka가 @KafkaListener 메서드 호출 직전 [intercept] 호출 →
 *   - record header `X-Trace-Id` 추출 (있으면 chain 유지)
 *   - 없으면 UUID 신규 생성 (진입점에서 trace 시작)
 *   - `MDC.put("traceId", id)` — listener method 본문 + 그 안의 모든 로그·downstream Kafka send에 부착
 *
 * listener 종료 후 [success]·[failure] hook에서 `MDC.remove("traceId")` —
 * 다음 record 처리·다른 listener 오염 방지.
 *
 * ### 등록 방법
 * `KafkaErrorHandlerConfig.kafkaListenerContainerFactory` 의 `factory.setRecordInterceptor(this)`로 일괄 등록.
 *
 * ### scope
 * - consumer 모듈: OrderRouterKafkaListener · SignalGeneratedKafkaListener · BoundaryClosingKafkaListener ·
 *   SymbolDetailCommandKafkaListener · WatchlistChangedKafkaListener · NotificationDispatcher 7건 · KisExecutionWsListener 등
 *   `kafkaListenerContainerFactory` (@Primary)를 사용하는 모든 listener.
 * - 예외: WatchlistChangedKafkaConfig는 별도 named factory — 본 interceptor 미적용 (slot router 영역 분리).
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
@Component
class TraceIdKafkaRecordInterceptor : RecordInterceptor<Any, Any> {
    override fun intercept(
        record: ConsumerRecord<Any, Any>,
        consumer: Consumer<Any, Any>,
    ): ConsumerRecord<Any, Any> {
        val incoming =
            record
                .headers()
                .lastHeader(HEADER_TRACE_ID)
                ?.value()
                ?.let { String(it, Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() }
        val traceId = incoming ?: generateTraceId()
        MDC.put(MDC_KEY_TRACE_ID, traceId)
        return record
    }

    override fun success(
        record: ConsumerRecord<Any, Any>,
        consumer: Consumer<Any, Any>,
    ) {
        MDC.remove(MDC_KEY_TRACE_ID)
    }

    override fun failure(
        record: ConsumerRecord<Any, Any>,
        exception: Exception,
        consumer: Consumer<Any, Any>,
    ) {
        MDC.remove(MDC_KEY_TRACE_ID)
    }

    /** UUID v4 32-hex (`-` 제거) — TraceIdServletFilter 정합. */
    private fun generateTraceId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        const val MDC_KEY_TRACE_ID = "traceId"
        const val HEADER_TRACE_ID = "X-Trace-Id"
    }
}
