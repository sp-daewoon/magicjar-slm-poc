package kr.spdaewoon.magicjar.api.messaging.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaRepository
import kr.spdaewoon.magicjar.api.persistence.signal.SignalJpaEntity
import kr.spdaewoon.magicjar.api.persistence.signal.SignalJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 매매일지 Kafka 이벤트 소비자 — 실제 체결 복기장.
 *
 * listen:
 * - order.filled.v1 → ORDER_FILLED entry INSERT
 *
 * 멱등성: DB UQ `ux_trading_journal_source` (source_table, source_id, event_type) 위반 시
 * [TradingJournalRepositoryAdapter]가 null 반환 → log.warn + skip. Kafka at-least-once 안전.
 *
 * 주문 거절·취소·시그널·리스크 차단은 감사/운영 로그 영역으로 남기고,
 * 매매일지에는 실제 매수/매도 체결과 그 전략 근거만 남긴다.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Component
class JournalEventConsumer(
    private val journalRepository: TradingJournalRepository,
    private val summaryComposer: JournalSummaryComposer,
    private val objectMapper: ObjectMapper,
    private val signalJpaRepository: SignalJpaRepository? = null,
    private val strategyJpaRepository: StrategyJpaRepository? = null,
    private val orderExecutionJpaRepository: OrderExecutionJpaRepository? = null,
    private val orderRequestJpaRepository: OrderRequestJpaRepository? = null,
    private val instrumentJpaRepository: InstrumentJpaRepository? = null,
) {
    private val log = LoggerFactory.getLogger(JournalEventConsumer::class.java)

    companion object {
        const val GROUP = "journal-event-consumer"
        private val ORDER_FILLED_QTY_KEYS =
            arrayOf("executedQty", "executedQuantity", "filledQuantity", "filledQty", "quantity")
        private val ORDER_FILLED_PRICE_KEYS =
            arrayOf("executedPrice", "filledPrice", "price")
    }

    /**
     * order.filled.v1 listen — 체결 이벤트 → trading_journal INSERT.
     *
     * payload 필드: orderId·userId·code·side·executedQty/executedQuantity/filledQty/quantity·
     *               executedPrice·executedAt·signalId·strategyId·kisOrderNo·partial·mode·instrumentName(nullable)
     */
    @KafkaListener(topics = [KafkaTopics.Order.FILLED], groupId = GROUP)
    fun onOrderFilled(record: ConsumerRecord<String, String>) {
        processEvent(record, KafkaTopics.Order.FILLED) { payload ->
            val userId = parseUserId(payload) ?: return@processEvent null
            val orderId = (payload["orderId"] as? Number)?.toLong() ?: return@processEvent null
            val code = payload["code"]?.toString() ?: return@processEvent null
            val side = payload["side"]?.toString() ?: "BUY"
            val executionId = payloadLong(payload, "executionId")
            val payloadExecutedAt = parseInstant(payload, "executedAt")
            val execution = resolveExecution(orderId, executionId, payloadExecutedAt)
            val qty = payloadBigDecimalAnyOf(payload, *ORDER_FILLED_QTY_KEYS) ?: execution?.executedQty ?: return@processEvent null
            val price = payloadBigDecimalAnyOf(payload, *ORDER_FILLED_PRICE_KEYS) ?: execution?.executedPrice ?: BigDecimal.ZERO
            val executedAt = payloadExecutedAt ?: execution?.executedAt ?: Instant.now()
            val signalId = payloadLong(payload, "signalId")
            val strategyId = payloadLong(payload, "strategyId")
            val signal = signalId?.let { signalJpaRepository?.findById(it)?.orElse(null) }
            val strategy = strategyId?.let { strategyJpaRepository?.findById(it)?.orElse(null) }
            val realizedSnapshot =
                resolveRealizedPnlSnapshot(
                    payload = payload,
                    side = side,
                    userId = userId,
                    executionId = executionId,
                    executedAt = executedAt,
                    execution = execution,
                )
            val instrumentName = resolveInstrumentName(code, payload["instrumentName"]?.toString())

            val enrichedPayload =
                enrichOrderFilledPayload(
                    payload = payload,
                    code = code,
                    side = side,
                    qty = qty,
                    price = price,
                    instrumentName = instrumentName,
                    signal = signal,
                    strategy = strategy,
                    realizedSnapshot = realizedSnapshot,
                )
            val summary = summaryComposer.compose(JournalEventType.ORDER_FILLED, enrichedPayload)

            TradingJournalEntry(
                userId = userId,
                eventType = JournalEventType.ORDER_FILLED,
                instrumentCode = code,
                instrumentName = instrumentName,
                occurredAt = executedAt,
                summary = summary,
                payload = objectMapper.writeValueAsString(enrichedPayload),
                sourceTable = "order_execution",
                sourceId = executionId ?: orderId,
                realizedPnl = realizedSnapshot?.realizedPnl,
            )
        }
    }

    // --- private helpers ---

    private data class RealizedPnlSnapshot(
        val realizedPnl: BigDecimal,
        val avgBuyPrice: BigDecimal? = null,
        val closedQuantity: BigDecimal? = null,
        val fee: BigDecimal? = null,
        val tax: BigDecimal? = null,
    )

    private data class PositionState(
        var qty: BigDecimal = BigDecimal.ZERO,
        var avgPrice: BigDecimal = BigDecimal.ZERO,
    )

    private fun resolveExecution(
        orderId: Long,
        executionId: Long?,
        payloadExecutedAt: Instant?,
    ): OrderExecutionJpaEntity? {
        val repository = orderExecutionJpaRepository ?: return null
        executionId?.let { id ->
            repository.findById(id).orElse(null)?.let { return it }
        }
        return repository
            .findByOrderIdOrderByExecutedAtAsc(orderId)
            .takeIf { it.isNotEmpty() }
            ?.let { executions ->
                payloadExecutedAt?.let { executedAt ->
                    executions.firstOrNull { it.executedAt == executedAt }
                } ?: executions.maxWithOrNull(compareBy<OrderExecutionJpaEntity> { it.executedAt }.thenBy { it.id ?: Long.MIN_VALUE })
            }
    }

    private fun resolveInstrumentName(
        code: String,
        payloadName: String?,
    ): String? {
        payloadName?.trim()?.takeIf { it.isNotBlank() && it != code }?.let { return it }
        return instrumentJpaRepository
            ?.findById(code)
            ?.orElse(null)
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != code }
    }

    private fun enrichOrderFilledPayload(
        payload: Map<String, Any?>,
        code: String,
        side: String,
        qty: BigDecimal,
        price: BigDecimal,
        instrumentName: String?,
        signal: SignalJpaEntity?,
        strategy: StrategyJpaEntity?,
        realizedSnapshot: RealizedPnlSnapshot?,
    ): Map<String, Any?> {
        val strategyDisplayName = strategy?.nameKo?.takeIf { it.isNotBlank() } ?: strategy?.name
        val extra =
            mutableMapOf<String, Any?>(
                "code" to code,
                "side" to side,
                "instrumentName" to instrumentName,
                "executedQty" to qty,
                "executedQuantity" to qty,
                "executedPrice" to price,
                "realizedPnl" to realizedSnapshot?.realizedPnl,
                "avgBuyPrice" to realizedSnapshot?.avgBuyPrice,
                "closedQuantity" to realizedSnapshot?.closedQuantity,
                "strategyName" to strategy?.name,
                "strategyNameKo" to strategy?.nameKo,
                "strategyDisplayName" to strategyDisplayName,
                "strategyTimeframe" to strategy?.tf,
                "strategyEntryDsl" to strategy?.dslEntry,
                "strategyExitDsl" to strategy?.dslExit,
                "strategyStopLossPct" to strategy?.fixedStopLossPct,
                "strategyTakeProfitPct" to strategy?.takeProfitPct,
                "signalReason" to signal?.reason,
                "signalStrength" to signal?.strength,
                "signalPrice" to signal?.price,
                "signalInterval" to signal?.interval,
                "signalOccurredAt" to signal?.ts?.toString(),
                "ruleSignal" to signal?.ruleSignal,
                "signalContext" to parseJsonValue(signal?.contextJson),
            )
        realizedSnapshot?.fee?.let { extra["realizedFee"] = it }
        realizedSnapshot?.tax?.let { extra["realizedTax"] = it }
        return payload + extra
    }

    private fun resolveRealizedPnlSnapshot(
        payload: Map<String, Any?>,
        side: String,
        userId: Long,
        executionId: Long?,
        executedAt: Instant,
        execution: OrderExecutionJpaEntity?,
    ): RealizedPnlSnapshot? {
        payloadBigDecimal(payload, "realizedPnl")?.let { return RealizedPnlSnapshot(realizedPnl = it) }
        execution?.realizedPnl?.let { return RealizedPnlSnapshot(realizedPnl = it) }
        if (!side.equals("SELL", ignoreCase = true)) return null
        return replayRealizedPnlSnapshot(userId, executionId, executedAt)
    }

    private fun replayRealizedPnlSnapshot(
        userId: Long,
        executionId: Long?,
        executedAt: Instant,
    ): RealizedPnlSnapshot? {
        if (executionId == null) return null
        val executionRepository = orderExecutionJpaRepository ?: return null
        val requestRepository = orderRequestJpaRepository ?: return null
        val executions =
            executionRepository
                .findByUserIdAndExecutedAtBefore(userId.toString(), executedAt.plusNanos(1))
                .sortedWith(compareBy<OrderExecutionJpaEntity> { it.executedAt }.thenBy { it.id ?: Long.MAX_VALUE })
        if (executions.none { it.id == executionId }) return null

        val requestById = requestRepository.findAllById(executions.map { it.orderId }.distinct()).associateBy { it.id }
        val positions = mutableMapOf<String, PositionState>()

        for (execution in executions) {
            val request = requestById[execution.orderId] ?: continue
            val state = positions.getOrPut(request.code) { PositionState() }
            val snapshot =
                when (request.side) {
                    OrderSide.BUY -> {
                        applyBuy(state, execution)
                        null
                    }
                    OrderSide.SELL -> applySell(state, execution)
                }
            if (execution.id == executionId) return snapshot
        }
        return null
    }

    private fun applyBuy(
        state: PositionState,
        execution: OrderExecutionJpaEntity,
    ) {
        if (execution.executedQty <= BigDecimal.ZERO) return

        val currentCost = state.qty.multiply(state.avgPrice)
        val addedCost =
            execution.executedQty
                .multiply(execution.executedPrice)
                .add(execution.fee)
                .add(execution.tax)
        val nextQty = state.qty.add(execution.executedQty)

        state.qty = nextQty
        state.avgPrice =
            if (nextQty.signum() > 0) {
                currentCost.add(addedCost).divide(nextQty, 8, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
    }

    private fun applySell(
        state: PositionState,
        execution: OrderExecutionJpaEntity,
    ): RealizedPnlSnapshot? {
        if (execution.executedQty <= BigDecimal.ZERO || state.qty <= BigDecimal.ZERO) return null

        val closedQty = minOf(execution.executedQty, state.qty)
        val avgBuyPrice = state.avgPrice
        val expenseRatio = closedQty.divide(execution.executedQty, 8, RoundingMode.HALF_UP)
        val fee = execution.fee.multiply(expenseRatio).setScale(4, RoundingMode.HALF_UP)
        val tax = execution.tax.multiply(expenseRatio).setScale(4, RoundingMode.HALF_UP)
        val realizedPnl =
            execution.executedPrice
                .subtract(avgBuyPrice)
                .multiply(closedQty)
                .subtract(fee)
                .subtract(tax)
                .setScale(2, RoundingMode.HALF_UP)

        state.qty = state.qty.subtract(closedQty)
        if (state.qty <= BigDecimal.ZERO) {
            state.qty = BigDecimal.ZERO
            state.avgPrice = BigDecimal.ZERO
        }

        return RealizedPnlSnapshot(
            realizedPnl = realizedPnl,
            avgBuyPrice = avgBuyPrice.setScale(4, RoundingMode.HALF_UP),
            closedQuantity = closedQty.stripTrailingZeros(),
            fee = fee.stripTrailingZeros(),
            tax = tax.stripTrailingZeros(),
        )
    }

    /**
     * 공통 이벤트 처리 템플릿.
     * [entryBuilder] null 반환 시 poison-pill로 간주 + log.warn + skip.
     */
    private fun processEvent(
        record: ConsumerRecord<String, String>,
        topic: String,
        entryBuilder: (Map<String, Any?>) -> TradingJournalEntry?,
    ) {
        try {
            val payload =
                deserializePayload(record.value()) ?: run {
                    log.warn("JournalEventConsumer deserialize 실패 (poison-pill skip): topic={} offset={}", topic, record.offset())
                    return
                }
            val entry =
                entryBuilder(payload) ?: run {
                    log.warn("JournalEventConsumer entry 빌드 실패 (필수 필드 누락): topic={} offset={}", topic, record.offset())
                    return
                }
            val saved = journalRepository.saveEntry(entry)
            if (saved == null) {
                log.warn(
                    "JournalEventConsumer UQ 충돌 skip (멱등성): topic={} sourceTable={} sourceId={} eventType={}",
                    topic,
                    entry.sourceTable,
                    entry.sourceId,
                    entry.eventType,
                )
            } else {
                log.info(
                    "JournalEventConsumer INSERT 완료: topic={} id={} userId={} eventType={}",
                    topic,
                    saved.id,
                    saved.userId,
                    saved.eventType,
                )
            }
        } catch (e: Exception) {
            log.error(
                "JournalEventConsumer 처리 실패: topic={} offset={} err={}",
                topic,
                record.offset(),
                e.message,
                e,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializePayload(value: String?): Map<String, Any?>? {
        if (value.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            log.error("JournalEventConsumer JSON 파싱 실패: err={}", e.message)
            null
        }
    }

    private fun parseUserId(payload: Map<String, Any?>): Long? {
        val raw = payload["userId"] ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun parseInstant(
        payload: Map<String, Any?>,
        key: String,
    ): Instant? {
        val raw = payload[key] ?: return null
        return when (raw) {
            is Number -> Instant.ofEpochSecond(raw.toLong())
            is String ->
                runCatching { Instant.parse(raw) }.getOrElse {
                    raw.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
                }
            else -> null
        }
    }

    private fun payloadLong(
        payload: Map<String, Any?>,
        key: String,
    ): Long? =
        when (val v = payload[key]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

    private fun payloadBigDecimalAnyOf(
        payload: Map<String, Any?>,
        vararg keys: String,
    ): BigDecimal? = keys.firstNotNullOfOrNull { key -> payloadBigDecimal(payload, key) }

    private fun payloadBigDecimal(
        payload: Map<String, Any?>,
        key: String,
    ): BigDecimal? =
        when (val v = payload[key]) {
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> v.toBigDecimalOrNull()
            else -> null
        }

    private fun parseJsonValue(value: String?): Any? {
        if (value.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(value, Any::class.java)
        } catch (e: Exception) {
            value
        }
    }
}
