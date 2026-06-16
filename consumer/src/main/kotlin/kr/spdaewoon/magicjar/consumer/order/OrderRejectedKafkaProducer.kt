package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * `order.rejected.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK + ADR 0044 진입).
 *
 * §14-2 step 3·4·7 REJECTED 분기 시 publish.
 * payload는 rejectReason 포함 (REAL_GATE_DENIED:cond / KILLSWITCH_ON / rt_cd:msg / KIS_5XX 등).
 *
 * partition key = userId.
 *
 * ### Transactional Outbox 패턴 (ADR 0084 P1-1 + spec §2-5 LOCK)
 *
 * 1. payload 직렬화 → outbox row INSERT (PENDING)
 * 2. Kafka send → outbox markPublished
 * 3. Kafka send 실패 시 outbox row 잔존 → OrderOutboxPublisherWorker가 비동기 retry
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §1-2)
 */
@Component
class OrderRejectedKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
) {
    private val log = LoggerFactory.getLogger(OrderRejectedKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.REJECTED
        private const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
    }

    fun publish(
        request: OrderRequest,
        reason: String,
    ) {
        // STOMP contract key 정합 (StompOrderNotifier.toStompPayload + OrderStompPayload):
        //   quantity     — request 주문 수량 (UX 표시 — "[REJECTED] 10주 거절")
        //   rejectReason — STOMP key (옛 "reason"은 StompOrderNotifier raw["rejectReason"]와 mismatch)
        // hotfix(2026-05-20): FILLED 결함과 동반 정합화.
        val payload =
            try {
                objectMapper.writeValueAsString(
                    mapOf(
                        "orderId" to request.id,
                        "userId" to request.userId,
                        "signalId" to request.signalId,
                        "strategyId" to request.strategyId,
                        "code" to request.code,
                        "side" to request.side.name,
                        "quantity" to request.quantity,
                        "rejectReason" to reason,
                        "mode" to request.mode.name,
                        "source" to request.source.name,
                        "rejectedAt" to (request.completedAt?.epochSecond ?: request.requestedAt.epochSecond),
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "OrderRejectedKafkaProducer payload 직렬화 실패: userId={} orderId={} err={}",
                    request.userId,
                    request.id,
                    e.message,
                )
                return
            }

        val outboxRepository = outboxRepositoryProvider.getIfAvailable()
        val outboxId =
            outboxRepository?.let {
                runCatching {
                    it
                        .save(
                            OrderEventOutbox(
                                eventType = OrderOutboxEventType.ORDER_REJECTED,
                                topic = TOPIC,
                                partitionKey = request.userId,
                                payloadJson = payload,
                            ),
                        ).id
                }.onFailure { e ->
                    log.error(
                        "OrderRejectedKafkaProducer outbox INSERT 실패 — direct publish fallback: userId={} orderId={} err={}",
                        request.userId,
                        request.id,
                        e.message,
                    )
                }.getOrNull()
            }

        try {
            kafkaTemplate
                .send(TOPIC, request.userId, payload)
                .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (outboxId != null) {
                runCatching { outboxRepository?.markPublished(outboxId) }
                    .onFailure { e ->
                        log.warn(
                            "OrderRejectedKafkaProducer outbox markPublished 실패 (publish는 성공): outboxId={} err={}",
                            outboxId,
                            e.message,
                        )
                    }
            }
            log.warn(
                "OrderRejectedKafkaProducer publish: topic={} userId={} orderId={} reason={}",
                TOPIC,
                request.userId,
                request.id,
                reason,
            )
        } catch (e: Exception) {
            if (outboxId != null) {
                runCatching {
                    outboxRepository?.recordAttemptFailure(outboxId, "publish: ${e.message?.take(200) ?: "unknown"}")
                }
            }
            log.error(
                "OrderRejectedKafkaProducer publish 실패 (outbox retry 예약): userId={} orderId={} outboxId={} err={}",
                request.userId,
                request.id,
                outboxId,
                e.message,
            )
        }
    }
}
