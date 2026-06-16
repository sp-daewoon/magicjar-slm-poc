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
 * `order.requested.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK).
 *
 * OrderRouterKafkaListener step 6에서 publish — ADR 0044 Notification 진입점.
 * Phase 2 backend-core가 listen → STOMP `/topic/order.user.{userId}` REQUESTED fanout.
 *
 * partition key = userId.
 *
 * ### Transactional Outbox 패턴 (ADR 0084 P1-1 + spec §2-5 LOCK)
 *
 * 1. payload 직렬화 → outbox row INSERT (PENDING)
 * 2. Kafka send (success 콜백) → outbox markPublished
 * 3. Kafka send 실패 시 outbox row 잔존 → OrderOutboxPublisherWorker가 비동기 retry
 *
 * outboxRepositoryProvider는 ObjectProvider — 어댑터 부재(테스트 단순화·rollback)에 graceful skip.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §1-2)
 */
@Component
class OrderRequestedKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
) {
    private val log = LoggerFactory.getLogger(OrderRequestedKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.REQUESTED
        private const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
    }

    fun publish(request: OrderRequest) {
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
                        "priceType" to request.priceType.name,
                        "quantity" to request.quantity,
                        "limitPrice" to request.limitPrice?.toPlainString(),
                        "status" to request.status.name,
                        "mode" to request.mode.name,
                        "source" to request.source.name,
                        "requestedAt" to request.requestedAt.epochSecond,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "OrderRequestedKafkaProducer payload 직렬화 실패: userId={} orderId={} err={}",
                    request.userId,
                    request.id,
                    e.message,
                )
                return
            }

        // 1단계 — outbox INSERT (PENDING)
        val outboxRepository = outboxRepositoryProvider.getIfAvailable()
        val outboxId =
            outboxRepository?.let {
                runCatching {
                    it
                        .save(
                            OrderEventOutbox(
                                eventType = OrderOutboxEventType.ORDER_REQUESTED,
                                topic = TOPIC,
                                partitionKey = request.userId,
                                payloadJson = payload,
                            ),
                        ).id
                }.onFailure { e ->
                    log.error(
                        "OrderRequestedKafkaProducer outbox INSERT 실패 — direct publish fallback: userId={} orderId={} err={}",
                        request.userId,
                        request.id,
                        e.message,
                    )
                }.getOrNull()
            }

        // 2단계 — Kafka publish
        try {
            kafkaTemplate
                .send(TOPIC, request.userId, payload)
                .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 3단계 — outbox markPublished (outboxId non-null 시 outboxRepository도 non-null 보장)
            if (outboxId != null) {
                runCatching { outboxRepository?.markPublished(outboxId) }
                    .onFailure { e ->
                        log.warn(
                            "OrderRequestedKafkaProducer outbox markPublished 실패 (publish는 성공): outboxId={} err={}",
                            outboxId,
                            e.message,
                        )
                    }
            }
            log.debug(
                "OrderRequestedKafkaProducer publish: topic={} userId={} orderId={} code={}",
                TOPIC,
                request.userId,
                request.id,
                request.code,
            )
        } catch (e: Exception) {
            // outbox row 잔존 — worker가 retry
            if (outboxId != null) {
                runCatching {
                    outboxRepository?.recordAttemptFailure(outboxId, "publish: ${e.message?.take(200) ?: "unknown"}")
                }
            }
            log.error(
                "OrderRequestedKafkaProducer publish 실패 (outbox retry 예약): userId={} orderId={} outboxId={} err={}",
                request.userId,
                request.id,
                outboxId,
                e.message,
            )
        }
    }
}
