package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ingestion.HoldingDemandRegistrar
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy
import kr.spdaewoon.magicjar.strategy.integrity.ReplayFillInput
import kr.spdaewoon.magicjar.strategy.integrity.ReplayFillPolicy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * 체결 즉시 take-profit LIMIT 선주문 + 보유 구독 연동 — ADR 0114 R2-C2b ② (Codex 흡수 + scale-in 정합 보강).
 *
 * ## 왜 선주문인가 (ADR 0114 §3-3 익절=LIMIT)
 * BUY 체결 시 전략 익절(`takeProfitPct`)이 있으면 **즉시 익절 LIMIT SELL을 KIS 서버에 걸어둔다**. 시스템(consumer)이
 * 다운돼도 익절 주문이 KIS 서버에 살아 있어 익절이 보장된다(1m 시그널 경로 의존 0 — 자본 보호 강화).
 *
 * ## Codex 약점 보강 (scale-in 정합 — 비교 머지 승부처)
 * Codex `AutoExitOrderCoordinator`는 체결별 개별 TP를 거나 평단 변동 시 단순 교체다. 우리는:
 * - **분할매수(scale-in) 평단 변동 정합**: 추가 체결로 평단이 바뀌면 기존 `auto-tp:` open SELL을 **취소 후
 *   합산 수량·새 평단 기준으로 재발행**(stale TP가 KIS에 남는 risk 제거). 이미 정합(동일 qty·price)이면 재발행 0.
 * - **계산 단일 출처**: 익절 LIMIT 가격은 R1 [EntryPricePolicy.takeProfitLimitPrice](평단·triggerPct) 재사용
 *   — 중복 산정 금지(ScaleInExitCalculator/calculator와 동일 평단 기준).
 *
 * ## PartialExit(분할 익절 plan) 경계 — 1m 경로 위임 (의도적 Codex 분기)
 * `partialExits`(다단계 익절)는 단계별 stage state(executedExitStages)를 [ScaleInExitCalculator]가 소유한다.
 * 모든 stage를 resting LIMIT으로 선주문하면 1m 분할 익절 신호와 **이중 발화**한다. 따라서 본 coordinator는
 * **단일 전량 익절(`takeProfitPct`)만 선주문**하고, 분할 익절 plan 전략은 선주문을 건너뛴다(1m 경로가 stage 발화).
 * 손절은 [PositionStopLossTickWatcher] tick 안전망이 별도 보장한다.
 *
 * ## 멱등 (1m 익절 신호와 충돌 방지)
 * `auto-tp:` 키([AutoOrderIdempotencyKeys.takeProfit] — userId·strategyId·code·qty·avgPrice·epoch). 평단·수량 불변이면
 * 동일 키 → OrderRouter `idem:order:{key}`가 1m 경로 익절 재발행을 흡수(이미 선주문이 걸려 있으면 1m 신호 중복 차단).
 *
 * ## 보유 구독 연동 (ADR 0114 §4-3 P3)
 * BUY 체결 시 [HoldingDemandRegistrar.onBuyFilled] + [HoldingPositionIndex.onFillRefresh](즉시 인덱스 반영).
 * SELL 체결로 전량 청산되면 release.
 *
 * env `magicjar.autotrading.exit.take-profit-limit-enabled`(default true).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class AutoExitOrderCoordinator(
    private val objectMapper: ObjectMapper,
    private val strategyRepository: ConsumerStrategyJpaRepository,
    private val strategyPositionRepository: StrategyPositionJpaRepository,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val kisOrderPort: KisOrderPort,
    private val orderCancelledKafkaProducer: OrderCancelledKafkaProducer,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    private val holdingPositionIndex: HoldingPositionIndex,
    private val holdingDemandRegistrar: HoldingDemandRegistrar,
    @Value("\${magicjar.autotrading.exit.take-profit-limit-enabled:true}")
    private val takeProfitLimitEnabled: Boolean = true,
    @Value("\${magicjar.autotrading.replay.freshness-threshold-seconds:120}")
    private val replayFreshnessThresholdSeconds: Long = 120,
) {
    private val log = LoggerFactory.getLogger(AutoExitOrderCoordinator::class.java)

    @KafkaListener(topics = [OrderFilledKafkaProducer.TOPIC], groupId = GROUP)
    fun onOrderFilled(record: ConsumerRecord<String, String>) {
        val event = parse(record) ?: return
        if (event.source != OrderSource.AUTO || event.strategyId <= 0L || event.executedQty <= 0) return

        when (event.side) {
            OrderSide.BUY -> handleBuyFill(event)
            OrderSide.SELL -> handleSellFill(event)
        }
    }

    private fun handleBuyFill(event: FilledEvent) {
        // 1. 보유 구독 보장 + 인덱스 즉시 반영 (ADR 0114 §4-3 P3) — TP 발행 실패와 무관하게 먼저.
        holdingDemandRegistrar.releasePendingBuy(event.orderId, event.code)
        holdingDemandRegistrar.onBuyFilled(event.code)
        holdingPositionIndex.onFillRefresh(event.code)

        // ADR 0115 §P1-2 replay 가드 — backfill/복구가 재발행한 과거 체결(REST_BACKFILL 또는 stale executedAt)은
        // 새 체결로 오인하지 않는다. 보유 인덱스·구독 갱신(위)은 유지하되 신규 TP 선주문은 발행하지 않는다(중복 익절 차단).
        if (isReplayFill(event)) {
            log.info(
                "auto take-profit skip(replay fill) — backfill/복구 재발행 체결 TP 미발행: userId={} strategyId={} code={} fillSource={} executedAt={}",
                event.userId,
                event.strategyId,
                event.code,
                event.fillSource,
                event.executedAtEpochSeconds,
            )
            return
        }

        if (!takeProfitLimitEnabled) return
        val strategy = runCatching { strategyRepository.findById(event.strategyId).orElse(null) }.getOrNull() ?: return
        val takeProfitPct = strategy.takeProfitPct?.takeIf { it > BigDecimal.ZERO } ?: return

        val position =
            strategyPositionRepository.findByUserIdAndStrategyIdAndCode(
                userId = event.userId,
                strategyId = event.strategyId,
                code = event.code,
            )
        val baseQuantity = position?.quantity?.takeIf { it > 0 } ?: event.executedQty
        val baseAvgPrice = position?.avgPrice?.takeIf { it > BigDecimal.ZERO } ?: event.executedPrice
        if (baseQuantity <= 0 || baseAvgPrice <= BigDecimal.ZERO) return

        // R1 단일 출처 — 익절 LIMIT 가격 = 평단 × (1 + takeProfitPct/100) → KRX 호가단위 내림.
        val targetPrice = EntryPricePolicy.takeProfitLimitPrice(baseAvgPrice, takeProfitPct)
        if (targetPrice <= BigDecimal.ZERO) return

        // scale-in 정합 — 기존 auto-tp open SELL 취소 후 재발행. 이미 정합이면 재발행 0(중복 방지).
        when (replaceOpenTakeProfitOrders(event, baseQuantity, targetPrice)) {
            TakeProfitReplaceResult.ALREADY_ALIGNED -> return
            TakeProfitReplaceResult.FAILED_CLOSED -> return
            TakeProfitReplaceResult.REPLACED_OR_EMPTY -> Unit
        }

        val idempotencyKey =
            AutoOrderIdempotencyKeys.takeProfit(
                event.userId,
                event.strategyId,
                event.code,
                baseQuantity,
                baseAvgPrice.toPlainString(),
                position?.updatedAt?.epochSecond ?: event.executedAtEpochSeconds ?: Instant.now().epochSecond,
            )

        orderIntentKafkaProducer.publishExitIntent(
            userId = event.userId,
            strategyId = event.strategyId,
            code = event.code,
            intervalCode = "1m",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = baseQuantity,
            limitPrice = targetPrice,
            priceAtSignal = baseAvgPrice,
            idempotencyKey = idempotencyKey,
            // ADR 0115 §2-P2-⑥ — 자동 익절 선주문: 발사 출처 AUTO_EXIT + 유발 근거 = 청산 대상 전략 id
            orderTrigger = OrderTrigger.AUTO_EXIT,
            triggerRefId = event.strategyId,
            signalId = event.signalId,
        )
        log.info(
            "auto take-profit LIMIT 선주문: userId={} strategyId={} code={} qty={} avgPrice={} limitPrice={} idemKey={}",
            event.userId,
            event.strategyId,
            event.code,
            baseQuantity,
            baseAvgPrice,
            targetPrice,
            idempotencyKey,
        )
    }

    /** 기존 auto-tp open SELL을 desired(qty·price)와 비교해 정합/재발행/실패 판정. */
    private fun replaceOpenTakeProfitOrders(
        event: FilledEvent,
        desiredQuantity: Int,
        desiredLimitPrice: BigDecimal,
    ): TakeProfitReplaceResult {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.error(
                "auto take-profit fail-closed: OrderRequestRepository 미합류 userId={} strategyId={} code={}",
                event.userId,
                event.strategyId,
                event.code,
            )
            return TakeProfitReplaceResult.FAILED_CLOSED
        }

        val openTakeProfits =
            orderRepo
                .findOpenByUserId(event.userId)
                .filter { order ->
                    order.code == event.code &&
                        order.strategyId == event.strategyId &&
                        order.side == OrderSide.SELL &&
                        order.source == OrderSource.AUTO &&
                        order.idempotencyKey.startsWith("${AutoOrderIdempotencyKeys.PREFIX_TAKE_PROFIT}:") &&
                        order.status in OPEN_STATUSES
                }
        if (openTakeProfits.isEmpty()) return TakeProfitReplaceResult.REPLACED_OR_EMPTY
        if (openTakeProfits.size == 1 && openTakeProfits.single().matchesTakeProfit(desiredQuantity, desiredLimitPrice)) {
            log.debug(
                "auto take-profit 정합(재발행 skip): userId={} strategyId={} code={} qty={} limitPrice={}",
                event.userId,
                event.strategyId,
                event.code,
                desiredQuantity,
                desiredLimitPrice,
            )
            return TakeProfitReplaceResult.ALREADY_ALIGNED
        }

        for (order in openTakeProfits) {
            if (order.kisOrderNo.isNullOrBlank() || order.kisFwdgOrgno.isNullOrBlank()) {
                log.warn(
                    "auto take-profit fail-closed: KIS ACK 미확정 기존 TP 존재(재발행 보류) userId={} code={} orderId={} status={}",
                    event.userId,
                    event.code,
                    order.id,
                    order.status,
                )
                return TakeProfitReplaceResult.FAILED_CLOSED
            }
            when (val cancelResult = kisOrderPort.cancel(order, order.mode)) {
                is OrderResult.Right -> publishCancelled(orderRepo, order)
                is OrderResult.Left -> {
                    log.warn(
                        "auto take-profit fail-closed: 기존 TP 취소 실패(재발행 보류) userId={} code={} orderId={} reason={}",
                        event.userId,
                        event.code,
                        order.id,
                        cancelResult.value,
                    )
                    return TakeProfitReplaceResult.FAILED_CLOSED
                }
            }
        }
        log.info(
            "auto take-profit replace(평단 변동): cancelled={} userId={} strategyId={} code={} nextQty={} nextPrice={}",
            openTakeProfits.size,
            event.userId,
            event.strategyId,
            event.code,
            desiredQuantity,
            desiredLimitPrice,
        )
        return TakeProfitReplaceResult.REPLACED_OR_EMPTY
    }

    private fun OrderRequest.matchesTakeProfit(
        desiredQuantity: Int,
        desiredLimitPrice: BigDecimal,
    ): Boolean = quantity == desiredQuantity && limitPrice?.compareTo(desiredLimitPrice) == 0

    private fun publishCancelled(
        orderRepo: OrderRequestRepository,
        order: OrderRequest,
    ) {
        val orderId = order.id
        val cancelled =
            (
                if (orderId != null) {
                    runCatching { orderRepo.markCompleted(orderId, Instant.now(), OrderStatus.CANCELLED) }
                        .onFailure { e -> log.error("auto take-profit cancel markCompleted 실패: orderId={} err={}", orderId, e.message) }
                        .getOrNull()
                } else {
                    null
                }
            ) ?: order.copy(status = OrderStatus.CANCELLED, completedAt = Instant.now())
        orderCancelledKafkaProducer.publish(cancelled)
    }

    private fun handleSellFill(event: FilledEvent) {
        val position =
            strategyPositionRepository.findByUserIdAndStrategyIdAndCode(
                userId = event.userId,
                strategyId = event.strategyId,
                code = event.code,
            )
        // 전량 청산(strategy 단위 잔량 0 + 종목 전체 보유 0) 시 HOLDING demand·인덱스 해제.
        if (position == null || position.quantity <= 0) {
            holdingPositionIndex.onFillRefresh(event.code)
            val remaining = holdingPositionIndex.heldBy(event.code)
            if (remaining.isEmpty()) {
                holdingDemandRegistrar.onFullyExited(event.code)
            }
        } else {
            // 부분 청산 — 평단·수량 변화로 기존 TP가 stale일 수 있어 인덱스만 갱신(TP 재발행은 다음 BUY 체결 또는 1m 경로).
            holdingPositionIndex.onFillRefresh(event.code)
        }
    }

    /** R1 [ReplayFillPolicy] 위임 — fillSource=REST_BACKFILL 1차 + executedAt 신선도 2중 판정. */
    private fun isReplayFill(event: FilledEvent): Boolean =
        ReplayFillPolicy.isReplayFill(
            ReplayFillInput(
                fillSource = event.fillSource,
                executedAtEpochSeconds = event.executedAtEpochSeconds,
                evalEpochSeconds = Instant.now().epochSecond,
                freshnessThresholdSeconds = replayFreshnessThresholdSeconds,
            ),
        )

    private fun parse(record: ConsumerRecord<String, String>): FilledEvent? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            FilledEvent(
                orderId = raw.longOrNull("orderId"),
                signalId = raw.longOrNull("signalId") ?: 0L,
                strategyId = raw.longOrNull("strategyId") ?: 0L,
                userId = raw["userId"]?.toString() ?: return null,
                code = raw["code"]?.toString() ?: return null,
                side = raw["side"]?.toString()?.let { runCatching { OrderSide.valueOf(it) }.getOrNull() } ?: return null,
                executedQty = raw.intOrNull("executedQty") ?: 0,
                executedPrice = raw.decimalOrNull("executedPrice") ?: return null,
                executedAtEpochSeconds = raw.longOrNull("executedAt"),
                fillSource = raw["fillSource"]?.toString(),
                source = raw["source"]?.toString()?.let { runCatching { OrderSource.valueOf(it) }.getOrNull() } ?: OrderSource.AUTO,
            )
        } catch (e: Exception) {
            log.warn("AutoExitOrderCoordinator order.filled parse skip: offset={} err={}", record.offset(), e.message)
            null
        }
    }

    private fun Map<String, Any?>.longOrNull(key: String): Long? =
        when (val raw = this[key]) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }

    private fun Map<String, Any?>.intOrNull(key: String): Int? =
        when (val raw = this[key]) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }

    private fun Map<String, Any?>.decimalOrNull(key: String): BigDecimal? =
        when (val raw = this[key]) {
            is Number -> BigDecimal.valueOf(raw.toDouble())
            is String -> raw.toBigDecimalOrNull()
            else -> null
        }

    internal data class FilledEvent(
        val orderId: Long?,
        val signalId: Long,
        val strategyId: Long,
        val userId: String,
        val code: String,
        val side: OrderSide,
        val executedQty: Int,
        val executedPrice: BigDecimal,
        val executedAtEpochSeconds: Long?,
        /** `order.filled.v1` payload fillSource("REST_BACKFILL"/"WS") — replay 1차 식별(ADR 0115 §P1-2). */
        val fillSource: String? = null,
        val source: OrderSource,
    )

    private enum class TakeProfitReplaceResult {
        REPLACED_OR_EMPTY,
        ALREADY_ALIGNED,
        FAILED_CLOSED,
    }

    private companion object {
        const val GROUP = "magicjar-consumer-auto-exit"
        val OPEN_STATUSES =
            setOf(OrderStatus.REQUESTED, OrderStatus.ACK, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_RECONCILE)
    }
}
