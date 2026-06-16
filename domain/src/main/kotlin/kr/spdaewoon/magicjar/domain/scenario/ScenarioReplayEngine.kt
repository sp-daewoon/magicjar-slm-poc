package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.math.BigDecimal

class ScenarioReplayEngine(
    private val strategyEvaluator: ScenarioStrategyEvaluator,
    private val riskEvaluator: ScenarioRiskEvaluator = ScenarioRiskEvaluator { ScenarioRiskDecision.Allowed },
    /**
     * 정규장 게이트 (readiness P1) — 라이브와 동일 boundary 판정.
     * boundaryTs(=candle.closedAt)가 영업일+정규장 안이 아니면 평가 skip + [ScenarioEventType.GATE_SKIPPED] 기록.
     * default = 항상 통과(게이트 미적용) — 기존 호출부·테스트 동작 보존.
     */
    private val marketHoursGate: ScenarioMarketHoursGate = ScenarioMarketHoursGate { true },
    /**
     * 라이브에는 있으나 리플레이 가상 상태로 재현 불가능한 게이트 목록 (readiness P1).
     * run 시작 시 1회 [ScenarioEventType.GATE_NOT_SIMULATED] 이벤트로 정직 표기 — 조용히 통과 금지.
     */
    private val notSimulatedGates: List<ScenarioNotSimulatedGate> = emptyList(),
    private val clock: Clock = Clock.System,
) {
    fun run(
        request: ScenarioReplayRequest,
        candles: List<ScenarioCandle>,
    ): ScenarioReplayResult {
        val startedAt = clock.now()
        val events = mutableListOf<ScenarioEvent>()
        val replayCandles = candles.filterFor(request)

        notSimulatedGates.forEach { gate -> events += gateNotSimulatedEvent(request, request.from, gate) }

        if (replayCandles.isEmpty()) {
            events +=
                request.event(
                    virtualTime = request.from,
                    type = ScenarioEventType.DATA_NOT_READY,
                    severity = ScenarioSeverity.ERROR,
                    title = "리플레이 candle 데이터 부족",
                    message = "리플레이 기간에 사용할 candle 데이터가 없어 가상 주문을 만들지 않았습니다.",
                )
            return ScenarioReplayResult.Aborted(request, events, startedAt, clock.now(), "DATA_NOT_READY")
        }

        val ledger = ScenarioLedger(request.initialCash, request.initialPositions)
        var cashBlockedBuy: CashBlockedBuy? = null
        replayCandles.forEachIndexed { index, candle ->
            events += candleEvent(request, candle)

            // 정규장 게이트 (readiness P1) — 라이브와 동일 boundary 판정. 장외/휴장 boundary는 평가 skip.
            if (!marketHoursGate.isWithinTradingHours(candle.closedAt)) {
                events += gateSkippedEvent(request, candle.closedAt)
                return@forEachIndexed
            }

            val context = ScenarioEvaluationContext(request, candle, replayCandles.take(index + 1), ledger.snapshot(candle.closedAt))
            val signal =
                runCatching { strategyEvaluator.evaluate(context) }
                    .getOrElse { error ->
                        events += strategyErrorEvent(request, candle.closedAt, error)
                        return@forEachIndexed
                    }

            if (signal.side != ScenarioSignalSide.BUY) {
                cashBlockedBuy = null
            }
            if (signal.side == ScenarioSignalSide.HOLD) {
                events += signalEvent(request, candle.closedAt, signal)
                return@forEachIndexed
            }

            val fault = request.faults.firstOrNull { it.at == candle.closedAt }
            if (fault != null) {
                events += faultEvent(request, candle.closedAt, fault)
                if (fault.blocksOrder(signal.side)) {
                    events += orderBlockedByFaultEvent(request, candle.closedAt, fault)
                    return@forEachIndexed
                }
            }

            val quantity = signal.quantity ?: defaultQuantity(signal.side, request.code, candle.close, ledger)
            if (quantity <= 0) {
                events += insufficientQuantityEvent(request, candle.closedAt, signal.side, ledger.availableQuantity(request.code), 1)
                return@forEachIndexed
            }

            if (signal.side == ScenarioSignalSide.SELL && ledger.availableQuantity(request.code) < quantity) {
                events += insufficientQuantityEvent(request, candle.closedAt, signal.side, ledger.availableQuantity(request.code), quantity)
                return@forEachIndexed
            }

            val nextCandle = replayCandles.getOrNull(index + 1)
            val fillPrice = nextCandle?.let { fillPrice(signal, it) }
            val repeatedCashBlockedBuy = cashBlockedBuy
            if (
                repeatedCashBlockedBuy != null &&
                signal.side == ScenarioSignalSide.BUY &&
                (fillPrice == null || !canAfford(ledger, quantity, fillPrice))
            ) {
                events +=
                    repeatedBuyFoldedEvent(
                        request,
                        candle.closedAt,
                        repeatedCashBlockedBuy,
                        quantity,
                        fillPrice ?: candle.close,
                        ledger.cash(),
                    )
                return@forEachIndexed
            }

            events += signalEvent(request, candle.closedAt, signal)

            val risk = riskEvaluator.evaluate(ScenarioRiskContext(request, candle, signal, quantity, ledger.snapshot(candle.closedAt)))
            events += riskEvent(request, candle.closedAt, risk)
            if (risk is ScenarioRiskDecision.Blocked) return@forEachIndexed

            events += orderRequestedEvent(request, candle.closedAt, signal, quantity)
            if (nextCandle == null) {
                events += fillNotDecidableEvent(request, candle.closedAt)
                return@forEachIndexed
            }

            if (fillPrice == null) {
                events += fillNotDecidableEvent(request, nextCandle.openedAt)
                return@forEachIndexed
            }

            if (signal.side == ScenarioSignalSide.BUY && !canAfford(ledger, quantity, fillPrice)) {
                events += insufficientCashEvent(request, nextCandle.openedAt, quantity, fillPrice, ledger.cash())
                cashBlockedBuy = CashBlockedBuy(nextCandle.openedAt, quantity, fillPrice, ledger.cash())
                return@forEachIndexed
            }
            cashBlockedBuy = null

            val snapshot =
                when (signal.side) {
                    ScenarioSignalSide.BUY -> ledger.buy(request.code, quantity, fillPrice, nextCandle.openedAt)
                    ScenarioSignalSide.SELL -> {
                        val sold = ledger.sell(request.code, quantity, fillPrice, nextCandle.openedAt)
                        // 분할 익절 체결 확정 시 단계 마킹(가격이 trigger 위에 머물러도 중복 발화 0 — spec §3-1·ADR 0111 §2-2).
                        // 부분 청산으로 보유분이 남았을 때만 의미 — 전량 청산은 reset되어 자동으로 단계 초기화.
                        signal.partialExitStageIndex?.let { stage ->
                            if (sold.quantity > 0) ledger.recordExitStage(request.code, stage)
                        }
                        sold
                    }
                    ScenarioSignalSide.HOLD -> null
                }
            events += orderFilledEvent(request, nextCandle.openedAt, signal, quantity, fillPrice)
            if (snapshot != null) {
                events += positionUpdatedEvent(request, nextCandle.openedAt, snapshot)
            }
        }

        val completedAt = clock.now()
        return ScenarioReplayResult.Success(
            request = request,
            events = events,
            ledger = ledger.snapshot(replayCandles.last().closedAt),
            startedAt = startedAt,
            completedAt = completedAt,
        )
    }

    private fun List<ScenarioCandle>.filterFor(request: ScenarioReplayRequest): List<ScenarioCandle> =
        filter { candle ->
            candle.code == request.code &&
                candle.interval == request.interval &&
                candle.closedAt >= request.from &&
                candle.closedAt <= request.to
        }.sortedBy { it.closedAt }

    private fun defaultQuantity(
        side: ScenarioSignalSide,
        code: String,
        price: BigDecimal,
        ledger: ScenarioLedger,
    ): Long =
        when (side) {
            ScenarioSignalSide.BUY -> ledger.maxAffordableQuantity(price).coerceAtMost(1)
            ScenarioSignalSide.SELL -> ledger.availableQuantity(code).coerceAtMost(1)
            ScenarioSignalSide.HOLD -> 0
        }

    private fun fillPrice(
        signal: ScenarioSignalDecision,
        nextCandle: ScenarioCandle,
    ): BigDecimal? =
        when (signal.priceType) {
            ScenarioOrderPriceType.MARKET -> nextCandle.open
            ScenarioOrderPriceType.LIMIT -> {
                val limit = signal.limitPrice ?: return null
                when (signal.side) {
                    ScenarioSignalSide.BUY -> if (nextCandle.low <= limit) limit else null
                    ScenarioSignalSide.SELL -> if (nextCandle.high >= limit) limit else null
                    ScenarioSignalSide.HOLD -> null
                }
            }
        }

    private fun ScenarioFault.blocksOrder(side: ScenarioSignalSide): Boolean =
        when (type) {
            ScenarioFaultType.BALANCE_LOOKUP_RATE_LIMITED,
            ScenarioFaultType.BALANCE_LOOKUP_FAILED,
            -> side == ScenarioSignalSide.SELL
            ScenarioFaultType.MARKET_CLOSED,
            ScenarioFaultType.DATA_NOT_READY,
            ScenarioFaultType.STRATEGY_TIMEOUT,
            -> true
        }

    private fun canAfford(
        ledger: ScenarioLedger,
        quantity: Long,
        fillPrice: BigDecimal,
    ): Boolean = ledger.cash() >= fillPrice.multiply(BigDecimal.valueOf(quantity))

    private fun candleEvent(
        request: ScenarioReplayRequest,
        candle: ScenarioCandle,
    ): ScenarioEvent =
        request.event(
            virtualTime = candle.closedAt,
            type = ScenarioEventType.CANDLE,
            severity = ScenarioSeverity.INFO,
            title = "리플레이 candle",
            message = "가상 시각 ${candle.closedAt} candle을 평가했습니다.",
            payload = mapOf("open" to candle.open, "high" to candle.high, "low" to candle.low, "close" to candle.close),
        )

    private fun signalEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        signal: ScenarioSignalDecision,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type =
                when (signal.side) {
                    ScenarioSignalSide.BUY -> ScenarioEventType.SIGNAL_BUY
                    ScenarioSignalSide.SELL -> ScenarioEventType.SIGNAL_SELL
                    ScenarioSignalSide.HOLD -> ScenarioEventType.SIGNAL_HOLD
                },
            severity = ScenarioSeverity.INFO,
            title = "리플레이 전략 신호 ${signal.side}",
            message = signal.reason ?: "가상 전략 평가 결과 ${signal.side} 신호가 생성됐습니다.",
            payload = mapOf("quantity" to signal.quantity, "priceType" to signal.priceType.name, "limitPrice" to signal.limitPrice),
        )

    private fun riskEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        risk: ScenarioRiskDecision,
    ): ScenarioEvent =
        when (risk) {
            ScenarioRiskDecision.Allowed ->
                request.event(
                    virtualTime = virtualTime,
                    type = ScenarioEventType.RISK_ALLOWED,
                    severity = ScenarioSeverity.INFO,
                    title = "리플레이 리스크 허용",
                    message = "가상 리스크 검사를 통과했습니다.",
                )
            is ScenarioRiskDecision.Blocked ->
                request.event(
                    virtualTime = virtualTime,
                    type = ScenarioEventType.RISK_BLOCKED,
                    severity = ScenarioSeverity.WARN,
                    title = "리플레이 리스크 차단",
                    message = risk.message,
                    payload = risk.payload + ("reasonCode" to risk.reasonCode),
                )
        }

    private fun repeatedBuyFoldedEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        blocked: CashBlockedBuy,
        quantity: Long,
        fillPrice: BigDecimal,
        cash: BigDecimal,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.SIGNAL_HOLD,
            severity = ScenarioSeverity.INFO,
            title = "리플레이 반복 BUY 접힘",
            message = "직전 BUY가 리플레이 현금 부족으로 미체결되어, 같은 BUY 조건이 계속되는 동안 반복 가상 BUY 주문을 접었습니다.",
            payload =
                mapOf(
                    "reasonCode" to "REPEATED_BUY_AFTER_CASH_BLOCK",
                    "firstBlockedAt" to blocked.at.toString(),
                    "firstRequiredCash" to blocked.requiredCash(),
                    "quantity" to quantity,
                    "fillPrice" to fillPrice,
                    "cash" to cash,
                    "requiredCash" to fillPrice.multiply(BigDecimal.valueOf(quantity)),
                ),
        )

    private fun orderRequestedEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        signal: ScenarioSignalDecision,
        quantity: Long,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.ORDER_REQUESTED,
            severity = ScenarioSeverity.INFO,
            title = "가상 주문 요청",
            message = "리플레이 ${signal.side} 가상 주문을 요청했습니다.",
            payload = mapOf("side" to signal.side.name, "quantity" to quantity),
        )

    private fun orderFilledEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        signal: ScenarioSignalDecision,
        quantity: Long,
        fillPrice: BigDecimal,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.ORDER_FILLED,
            severity = ScenarioSeverity.INFO,
            title = "가상 주문 체결",
            message = "리플레이 ${signal.side} 가상 주문이 ${fillPrice}원에 체결됐습니다.",
            payload = mapOf("side" to signal.side.name, "quantity" to quantity, "fillPrice" to fillPrice),
        )

    private fun positionUpdatedEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        snapshot: ScenarioPositionSnapshot,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.POSITION_UPDATED,
            severity = ScenarioSeverity.INFO,
            title = "리플레이 포지션 갱신",
            message = "가상 포지션이 ${snapshot.quantity}주로 갱신됐습니다.",
            payload = mapOf("quantity" to snapshot.quantity, "averagePrice" to snapshot.averagePrice),
        )

    private fun insufficientQuantityEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        side: ScenarioSignalSide,
        holdingQuantity: Long,
        requestedQuantity: Long,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.RISK_BLOCKED,
            severity = ScenarioSeverity.WARN,
            title = "리플레이 매도 가능 수량 부족",
            message = "리플레이 포지션 기준 매도 가능 수량이 부족해 가상 $side 주문을 만들지 않았습니다.",
            payload = mapOf("holdingQuantity" to holdingQuantity, "requestedQuantity" to requestedQuantity),
        )

    private fun faultEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        fault: ScenarioFault,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.FAULT_INJECTED,
            severity = ScenarioSeverity.WARN,
            title = "리플레이 장애 주입",
            message = fault.message ?: "리플레이 장애 ${fault.type}를 적용했습니다.",
            payload = mapOf("faultType" to fault.type.name),
        )

    private fun orderBlockedByFaultEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        fault: ScenarioFault,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type =
                if (fault.type == ScenarioFaultType.MARKET_CLOSED) {
                    ScenarioEventType.ORDER_REJECTED
                } else {
                    ScenarioEventType.ORDER_HELD
                },
            severity = ScenarioSeverity.WARN,
            title =
                if (fault.type == ScenarioFaultType.MARKET_CLOSED) {
                    "가상 주문 거절"
                } else {
                    "가상 주문 보류"
                },
            message = heldMessage(fault.type),
            payload = mapOf("faultType" to fault.type.name),
        )

    private fun heldMessage(type: ScenarioFaultType): String =
        when (type) {
            ScenarioFaultType.BALANCE_LOOKUP_RATE_LIMITED -> "KIS 잔고 조회 rate limit으로 리플레이 가상 주문을 보류했습니다."
            ScenarioFaultType.BALANCE_LOOKUP_FAILED -> "잔고 조회 실패로 리플레이 가상 주문을 발행하지 않았습니다."
            ScenarioFaultType.MARKET_CLOSED -> "리플레이 시장 세션 규칙상 가상 주문을 거절했습니다."
            ScenarioFaultType.DATA_NOT_READY -> "리플레이 데이터 준비 상태가 부족해 가상 주문을 보류했습니다."
            ScenarioFaultType.STRATEGY_TIMEOUT -> "리플레이 전략 평가 timeout으로 가상 주문을 보류했습니다."
        }

    private fun insufficientCashEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        quantity: Long,
        fillPrice: BigDecimal,
        cash: BigDecimal,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.RISK_BLOCKED,
            severity = ScenarioSeverity.WARN,
            title = "리플레이 현금 부족",
            message = "리플레이 현금이 부족해 가상 BUY 주문을 체결하지 않았습니다.",
            payload =
                mapOf(
                    "quantity" to quantity,
                    "fillPrice" to fillPrice,
                    "cash" to cash,
                    "requiredCash" to fillPrice.multiply(BigDecimal.valueOf(quantity)),
                ),
        )

    private fun fillNotDecidableEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.ORDER_HELD,
            severity = ScenarioSeverity.WARN,
            title = "가상 체결 판단 보류",
            message = "다음 candle이 없어 리플레이 가상 체결을 보수적으로 보류했습니다.",
        )

    private fun gateSkippedEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.GATE_SKIPPED,
            severity = ScenarioSeverity.INFO,
            title = "리플레이 정규장 게이트 skip",
            message =
                "가상 시각 ${virtualTime}은 영업일+정규장(09:00~15:30 KST)이 아니라 " +
                    "라이브와 동일하게 평가를 건너뛰었습니다. 이 boundary는 라이브에서도 시그널이 발화되지 않습니다.",
            payload = mapOf("gate" to "MARKET_HOURS", "reasonCode" to "GATE_SKIPPED:MARKET_HOURS"),
        )

    private fun gateNotSimulatedEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        gate: ScenarioNotSimulatedGate,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.GATE_NOT_SIMULATED,
            severity = ScenarioSeverity.WARN,
            title = "리플레이 미재현 게이트: ${gate.rule}",
            message =
                gate.note
                    ?: "라이브 게이트 ${gate.rule}는 실계좌 상태에 의존해 리플레이 가상 상태로 재현할 수 없습니다. " +
                    "리플레이 decision은 이 게이트를 통과한 것으로 가정하므로 라이브와 차이가 날 수 있습니다.",
            payload = mapOf("gate" to gate.rule, "reasonCode" to "GATE_NOT_SIMULATED:${gate.rule}"),
        )

    private fun strategyErrorEvent(
        request: ScenarioReplayRequest,
        virtualTime: Instant,
        error: Throwable,
    ): ScenarioEvent =
        request.event(
            virtualTime = virtualTime,
            type = ScenarioEventType.STRATEGY_ERROR,
            severity = ScenarioSeverity.ERROR,
            title = "리플레이 전략 평가 오류",
            message = "전략 평가 오류로 해당 가상 시각의 주문 평가를 건너뜁니다: ${error.message ?: error::class.simpleName}",
        )

    private fun ScenarioReplayRequest.event(
        virtualTime: Instant,
        type: ScenarioEventType,
        severity: ScenarioSeverity,
        title: String,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ): ScenarioEvent =
        ScenarioEvent(
            virtualTime = virtualTime,
            type = type,
            severity = severity,
            title = title,
            message = message,
            strategyId = strategyId,
            strategyName = strategyName,
            code = code,
            payload = payload,
        )
}

private data class CashBlockedBuy(
    val at: Instant,
    val quantity: Long,
    val fillPrice: BigDecimal,
    val cash: BigDecimal,
) {
    fun requiredCash(): BigDecimal = fillPrice.multiply(BigDecimal.valueOf(quantity))
}

fun interface ScenarioStrategyEvaluator {
    fun evaluate(context: ScenarioEvaluationContext): ScenarioSignalDecision
}

fun interface ScenarioRiskEvaluator {
    fun evaluate(context: ScenarioRiskContext): ScenarioRiskDecision
}

/**
 * 리플레이 정규장 게이트 (readiness P1) — 라이브 [kr.spdaewoon.magicjar.domain.market.session.TradingHoursGate] 위임.
 * boundaryTs(=candle.closedAt)가 영업일+정규장 안인지 라이브와 동일하게 판정한다.
 */
fun interface ScenarioMarketHoursGate {
    fun isWithinTradingHours(boundaryTs: kotlinx.datetime.Instant): Boolean
}

/**
 * 리플레이 가상 상태로 재현 불가능한 라이브 게이트 표기 (readiness P1).
 * 조용히 통과시키지 않고 [ScenarioEventType.GATE_NOT_SIMULATED]로 정직 노출하기 위한 메타.
 */
data class ScenarioNotSimulatedGate(
    val rule: String,
    val note: String? = null,
) {
    init {
        require(rule.isNotBlank()) { "rule must be non-blank" }
    }
}

data class ScenarioEvaluationContext(
    val request: ScenarioReplayRequest,
    val candle: ScenarioCandle,
    val candlesUntilNow: List<ScenarioCandle>,
    val ledger: ScenarioLedgerSnapshot,
)

data class ScenarioRiskContext(
    val request: ScenarioReplayRequest,
    val candle: ScenarioCandle,
    val signal: ScenarioSignalDecision,
    val quantity: Long,
    val ledger: ScenarioLedgerSnapshot,
)

sealed class ScenarioReplayResult {
    abstract val request: ScenarioReplayRequest
    abstract val events: List<ScenarioEvent>
    abstract val startedAt: Instant
    abstract val completedAt: Instant

    data class Success(
        override val request: ScenarioReplayRequest,
        override val events: List<ScenarioEvent>,
        val ledger: ScenarioLedgerSnapshot,
        override val startedAt: Instant,
        override val completedAt: Instant,
    ) : ScenarioReplayResult()

    data class Aborted(
        override val request: ScenarioReplayRequest,
        override val events: List<ScenarioEvent>,
        override val startedAt: Instant,
        override val completedAt: Instant,
        val reasonCode: String,
    ) : ScenarioReplayResult()
}
