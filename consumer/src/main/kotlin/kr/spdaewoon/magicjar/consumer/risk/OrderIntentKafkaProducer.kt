package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * 주문 의도(order.intent.v1) Kafka publish 어댑터 (ADR 0067 §7-1 + E-1 정합).
 *
 * partition key = `userId` — 사용자별 ordering 보장.
 *
 * payload schema (Phase 1 자체 정의 — T-O ADR 진입 시 확정):
 * { signalId, strategyId, userId, code, interval, ts(epochSeconds), side, strength,
 *   priceAtSignal, riskVerdict: "ALLOWED", emittedAt(epochSeconds) }
 *
 * spec 인용: ADR 0067 §7-1 OrderIntentKafkaProducer.
 *
 * ### Transactional Outbox 패턴 (ADR 0084 P1-1 + spec §2-5 LOCK)
 *
 * 1. payload 직렬화 → outbox row INSERT (PENDING)
 * 2. Kafka send → outbox markPublished
 * 3. Kafka send 실패 시 outbox row 잔존 → OrderOutboxPublisherWorker가 비동기 retry
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0084 §1-2)
 */
@Component
class OrderIntentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
    // ADR 0094 P0-OPS-6 + ADR 0095 §2-1 S-1 — signal emit ~ order intent publish latency Timer
    private val persistenceMetricsProvider: ObjectProvider<PersistenceMetrics>,
) {
    private val log = LoggerFactory.getLogger(OrderIntentKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.INTENT
        private const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
    }

    /**
     * order.intent.v1 발행.
     *
     * ADR 0114 §3-1 — 진입(BUY)이 marketable limit이면 [priceType] = [OrderPriceType.LIMIT] + [limitPrice]를 전달한다.
     * SELL·미산정(현행)은 default([OrderPriceType.MARKET]·null) 유지(하위호환 LOCK — 손절은 §3-3 MARKET 정합).
     *
     * @param priceType 주문 가격 유형(default MARKET — 현행 동작 보존).
     * @param limitPrice LIMIT 시 not null(MARKET이면 무시 — null 권장).
     */
    fun publish(
        signal: Signal,
        quantity: Int = 1,
        priceType: OrderPriceType = OrderPriceType.MARKET,
        limitPrice: BigDecimal? = null,
    ) {
        val now =
            java.time.Instant
                .now()
                .epochSecond
        val effectiveLimitPrice = limitPrice?.takeIf { priceType == OrderPriceType.LIMIT }
        val payload =
            try {
                objectMapper.writeValueAsString(
                    mapOf(
                        "signalId" to signal.id,
                        "strategyId" to signal.strategyId,
                        "userId" to signal.userId,
                        "code" to signal.code,
                        "interval" to signal.interval.code,
                        "ts" to signal.ts.epochSeconds,
                        "side" to signal.side.name,
                        "strength" to signal.strength,
                        "priceAtSignal" to signal.priceAtSignal.toPlainString(),
                        "priceType" to priceType.name,
                        "quantity" to quantity,
                        "limitPrice" to effectiveLimitPrice?.toPlainString(),
                        "idempotencyKey" to "${signal.id}:${signal.userId}",
                        "source" to "AUTO",
                        // ADR 0115 §2-P2-⑥ — 시그널 파생 주문: 발사 출처 STRATEGY_SIGNAL + 유발 근거 = signal id
                        "orderTrigger" to OrderTrigger.STRATEGY_SIGNAL.name,
                        "triggerRefId" to signal.id,
                        "riskVerdict" to "ALLOWED",
                        "emittedAt" to now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "OrderIntentKafkaProducer payload 직렬화 실패: userId={} signalId={} err={}",
                    signal.userId,
                    signal.id,
                    e.message,
                )
                return
            }

        sendWithOutbox(
            userId = signal.userId,
            signalId = signal.id,
            payload = payload,
            latencyTsEpoch = signal.ts.epochSeconds,
            emittedAt = now,
        )
    }

    /**
     * 시그널 경로를 우회하는 **청산 안전망 intent** 발행 (ADR 0114 R2-C2b — tick 손절 watcher·TP 선주문).
     *
     * 1m 시그널 경로([publish] with [Signal])와 달리 실제 `Signal` 행 없이도 OrderIntent를 발행한다
     * (tick 손절·체결 즉시 TP 선주문은 시그널 발화·readiness·market-hours 게이트를 구조적으로 우회하는
     *  독립 안전망 — ADR 0114 §4-1·§4-2). [OrderRouterKafkaListener.deserialize]가 동일 payload schema를
     * 읽으므로 멱등 키([idempotencyKey])·source(AUTO)가 그대로 흘러 OrderRouter 멱등 가드(`idem:order:{key}`)에 정합한다.
     *
     * @param signalId 0L 권장(시그널 비파생). canary notional cap·audit 추적용.
     * @param priceAtSignal MARKET canary cap 단가 source(현재가/평단). LIMIT이면 [limitPrice]가 우선.
     * @param idempotencyKey **이중 발화 방지 핵심** — `auto-sl:`/`auto-tp:` prefix(AutoOrderIdempotencyKeys). 동일 키 재발행은 OrderRouter가 흡수.
     * @param orderTrigger 발사 출처 enum (ADR 0115 §2-P2-⑥ — AUTO_EXIT/STOP_LOSS/PARTIAL_TAKE_PROFIT). "출처 모르는 주문" 폐지.
     * @param triggerRefId 유발 근거 실키 (청산 대상 strategy id 등). signal 없는 청산도 근거 키 보존.
     */
    fun publishExitIntent(
        userId: String,
        strategyId: Long,
        code: String,
        intervalCode: String,
        side: kr.spdaewoon.magicjar.domain.order.execution.OrderSide,
        priceType: OrderPriceType,
        quantity: Int,
        limitPrice: BigDecimal?,
        priceAtSignal: BigDecimal,
        idempotencyKey: String,
        orderTrigger: OrderTrigger,
        triggerRefId: Long,
        signalId: Long = 0L,
    ) {
        val now =
            java.time.Instant
                .now()
                .epochSecond
        val effectiveLimitPrice = limitPrice?.takeIf { priceType == OrderPriceType.LIMIT }
        val payload =
            try {
                objectMapper.writeValueAsString(
                    mapOf(
                        "signalId" to signalId,
                        "strategyId" to strategyId,
                        "userId" to userId,
                        "code" to code,
                        "interval" to intervalCode,
                        "ts" to now,
                        "side" to side.name,
                        "strength" to 100,
                        "priceAtSignal" to priceAtSignal.toPlainString(),
                        "priceType" to priceType.name,
                        "quantity" to quantity,
                        "limitPrice" to effectiveLimitPrice?.toPlainString(),
                        "idempotencyKey" to idempotencyKey,
                        "source" to "AUTO",
                        // ADR 0115 §2-P2-⑥ — 청산 안전망 주문도 발사 출처·근거 키 보존 (signal_id=0 sentinel 의미 제거)
                        "orderTrigger" to orderTrigger.name,
                        "triggerRefId" to triggerRefId,
                        "riskVerdict" to "ALLOWED",
                        "emittedAt" to now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "OrderIntentKafkaProducer exit intent 직렬화 실패: userId={} code={} idemKey={} err={}",
                    userId,
                    code,
                    idempotencyKey,
                    e.message,
                )
                return
            }

        sendWithOutbox(userId = userId, signalId = signalId, payload = payload, latencyTsEpoch = null, emittedAt = now)
    }

    /**
     * outbox INSERT → Kafka send → markPublished 공통 경로 (DRY — [publish]·[publishExitIntent] 공유).
     * latencyTsEpoch 가 not null이면 S-1(signal→order) latency Timer record.
     */
    private fun sendWithOutbox(
        userId: String,
        signalId: Long?,
        payload: String,
        latencyTsEpoch: Long?,
        emittedAt: Long,
    ) {
        val outboxRepository = outboxRepositoryProvider.getIfAvailable()
        val outboxId =
            outboxRepository?.let {
                runCatching {
                    it
                        .save(
                            OrderEventOutbox(
                                eventType = OrderOutboxEventType.ORDER_INTENT,
                                topic = TOPIC,
                                partitionKey = userId,
                                payloadJson = payload,
                            ),
                        ).id
                }.onFailure { e ->
                    log.error(
                        "OrderIntentKafkaProducer outbox INSERT 실패 — direct publish fallback: userId={} signalId={} err={}",
                        userId,
                        signalId,
                        e.message,
                    )
                }.getOrNull()
            }

        try {
            kafkaTemplate
                .send(TOPIC, userId, payload)
                .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (outboxId != null) {
                runCatching { outboxRepository?.markPublished(outboxId) }
                    .onFailure { e ->
                        log.warn(
                            "OrderIntentKafkaProducer outbox markPublished 실패 (publish는 성공): outboxId={} err={}",
                            outboxId,
                            e.message,
                        )
                    }
            }
            // ADR 0095 S-1 측정 — signal.ts(epoch s) → now(epoch s) latency Timer record (시그널 파생만)
            if (latencyTsEpoch != null) {
                persistenceMetricsProvider
                    .getIfAvailable()
                    ?.recordSignalToOrderLatency(latencyTsEpoch, emittedAt)
            }
            log.debug(
                "OrderIntentKafkaProducer publish: topic={} userId={} signalId={}",
                TOPIC,
                userId,
                signalId,
            )
        } catch (e: Exception) {
            if (outboxId != null) {
                runCatching {
                    outboxRepository?.recordAttemptFailure(outboxId, "publish: ${e.message?.take(200) ?: "unknown"}")
                }
            }
            log.error(
                "OrderIntentKafkaProducer publish 실패 (outbox retry 예약): userId={} signalId={} outboxId={} err={}",
                userId,
                signalId,
                outboxId,
                e.message,
            )
        }
    }
}
