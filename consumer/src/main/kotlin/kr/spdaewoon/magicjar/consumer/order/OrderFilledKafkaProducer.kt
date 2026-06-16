package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * `order.filled.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK + T-RECON 합류점).
 *
 * KisExecutionWsListener CNTG_YN="2" full fill 시 publish.
 * Phase 2 backend-core listen → STOMP FILLED fanout + ADR 0044 Notification.
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
class OrderFilledKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
) {
    private val log = LoggerFactory.getLogger(OrderFilledKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.FILLED
        private const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
    }

    fun publish(
        request: OrderRequest,
        execution: OrderExecution,
        partial: Boolean,
    ) {
        // STOMP contract key 정합 (StompOrderNotifier.toStompPayload + OrderStompPayload):
        //   quantity      — request 주문 수량 (UX 표시 — "[FILLED] 10주 @ 73,000원")
        //   executedQty   — 체결 수량 (frontend useStompReconnect.ts L104 `qty ?? executedQty` path)
        //   rejectReason  — null on FILLED
        // hotfix(2026-05-20): 옛 publish key "executedQuantity"가 StompOrderNotifier raw["executedQty"]와
        //   mismatch → frontend "0주" toast. publisher contract를 STOMP key와 통일.
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
                        "executionId" to execution.id,
                        "kisOrderNo" to request.kisOrderNo,
                        "quantity" to request.quantity,
                        "executedQty" to execution.executedQuantity,
                        "executedPrice" to execution.executedPrice.toPlainString(),
                        "executedAt" to execution.executedAt.epochSecond,
                        "fillSource" to fillSource(execution),
                        "partial" to partial,
                        "mode" to request.mode.name,
                        "source" to request.source.name,
                        // ADR 0115 §2-P2-⑥ — 체결 알림 출처 표기 개선 (자동청산/손절/시그널/외부 구분)
                        "orderTrigger" to request.orderTrigger?.name,
                        "triggerRefId" to request.triggerRefId,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "OrderFilledKafkaProducer payload 직렬화 실패: userId={} orderId={} err={}",
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
                                eventType = OrderOutboxEventType.ORDER_FILLED,
                                topic = TOPIC,
                                partitionKey = request.userId,
                                payloadJson = payload,
                            ),
                        ).id
                }.onFailure { e ->
                    log.error(
                        "OrderFilledKafkaProducer outbox INSERT 실패 — direct publish fallback: userId={} orderId={} err={}",
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
                            "OrderFilledKafkaProducer outbox markPublished 실패 (publish는 성공): outboxId={} err={}",
                            outboxId,
                            e.message,
                        )
                    }
            }
            log.info(
                "OrderFilledKafkaProducer publish: topic={} userId={} orderId={} partial={} qty={}",
                TOPIC,
                request.userId,
                request.id,
                partial,
                execution.executedQuantity,
            )
        } catch (e: Exception) {
            if (outboxId != null) {
                runCatching {
                    outboxRepository?.recordAttemptFailure(outboxId, "publish: ${e.message?.take(200) ?: "unknown"}")
                }
            }
            log.error(
                "OrderFilledKafkaProducer publish 실패 (outbox retry 예약): userId={} orderId={} outboxId={} err={}",
                request.userId,
                request.id,
                outboxId,
                e.message,
            )
        }
    }

    private fun fillSource(execution: OrderExecution): String =
        if (execution.eventId?.startsWith(KisExecutionBackfillService.BACKFILL_EVENT_ID_PREFIX) == true) {
            "REST_BACKFILL"
        } else {
            "WS"
        }
}
