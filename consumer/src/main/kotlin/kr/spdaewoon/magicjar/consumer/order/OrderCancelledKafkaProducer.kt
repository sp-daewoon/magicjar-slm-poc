package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `order.cancelled.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK + ADR 0044 진입).
 *
 * KisExecutionWsListener ACPT_YN="3" (취소 접수 통보) 또는 Phase 2 OrderCancelService 성공 시 publish.
 *
 * partition key = userId.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderCancelledKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderCancelledKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.CANCELLED
    }

    fun publish(request: OrderRequest) {
        try {
            // STOMP contract key 정합 (StompOrderNotifier.toStompPayload + OrderStompPayload):
            //   quantity — request 주문 수량 (UX 표시 — "[CANCELLED] 10주 취소")
            // hotfix(2026-05-20): FILLED·REJECTED 결함과 동반 정합화 — 취소 toast "0주" 회피.
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "orderId" to request.id,
                        "userId" to request.userId,
                        "signalId" to request.signalId,
                        "strategyId" to request.strategyId,
                        "code" to request.code,
                        "side" to request.side.name,
                        "quantity" to request.quantity,
                        "kisOrderNo" to request.kisOrderNo,
                        "mode" to request.mode.name,
                        "cancelledAt" to (request.completedAt?.epochSecond ?: request.requestedAt.epochSecond),
                    ),
                )
            kafkaTemplate.send(TOPIC, request.userId, payload)
            log.info(
                "OrderCancelledKafkaProducer publish: topic={} userId={} orderId={}",
                TOPIC,
                request.userId,
                request.id,
            )
        } catch (e: Exception) {
            log.error(
                "OrderCancelledKafkaProducer publish 실패: userId={} orderId={} err={}",
                request.userId,
                request.id,
                e.message,
            )
        }
    }
}
