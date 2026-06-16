package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.consumer.order.AutoExitReservations.isCancellableAutoExit
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.risk.RiskBudgetCalculator
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Risk Gateway 평가 본체 (ADR 0067 §5-5 + §7-2).
 *
 * ### 평가 순서 (short-circuit — 첫 BLOCKED 발견 시 즉시 반환)
 *  1. [RiskRule.KILL_SWITCH_ON]
 *  2. [RiskRule.DAILY_LOSS_LIMIT]
 *  3. [RiskRule.MAX_CONCURRENT_POSITIONS]   (BUY만)
 *  4. [RiskRule.TRADE_FREQUENCY_MINUTE]
 *  5. [RiskRule.TRADE_FREQUENCY_DAY]
 *  6. [RiskRule.POSITION_LIMIT]            (BUY만)
 *
 * ### Pure logic (deterministic)
 * 외부 I/O 0건 — RiskContext에 모든 입력 사전 빌드 후 호출.
 *
 * spec 인용: ADR 0067 §5-5 + §7-2.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class RiskGatewayEvaluator {
    private val log = LoggerFactory.getLogger(RiskGatewayEvaluator::class.java)

    fun evaluate(
        signal: Signal,
        context: RiskContext,
    ): RiskGatewayResult {
        val signalId = signal.id ?: error("Signal.id must be non-null for risk evaluation")

        // 1. KILL_SWITCH_ON — ADR 0043 정합 (최우선)
        if (context.killSwitchOn) {
            return blocked(
                signalId,
                RiskRule.KILL_SWITCH_ON,
                reason = "KillSwitch enabled for user=${context.userId}",
                context = mapOf("userId" to context.userId),
            )
        }

        if (signal.priceAtSignal <= BigDecimal.ZERO) {
            return blocked(
                signalId,
                RiskRule.INVALID_SIGNAL_PRICE,
                reason = "Signal price must be positive: ${signal.priceAtSignal.toPlainString()}",
                context =
                    mapOf(
                        "priceAtSignal" to signal.priceAtSignal.toPlainString(),
                        "side" to signal.side.name,
                        "code" to signal.code,
                    ),
            )
        }

        // 2. DAILY_LOSS_LIMIT — B-1 amount OR percent xor
        if (context.config.isDailyLossLimitActive) {
            val pnl = context.dailyRealizedPnl
            val amountLimit = context.config.dailyLossLimitAmount
            val percentLimit = context.config.dailyLossLimitPercent

            if (amountLimit != null) {
                if (pnl < BigDecimal.ZERO && pnl.abs() >= amountLimit) {
                    return blocked(
                        signalId,
                        RiskRule.DAILY_LOSS_LIMIT,
                        reason = "Daily loss ${pnl.toPlainString()} reached amount limit -${amountLimit.toPlainString()}",
                        context =
                            mapOf(
                                "dailyRealizedPnl" to pnl.toPlainString(),
                                "limit" to amountLimit.toPlainString(),
                                "limitType" to "amount",
                            ),
                    )
                }
            } else if (percentLimit != null) {
                if (context.currentCapital > BigDecimal.ZERO && pnl < BigDecimal.ZERO) {
                    val pnlPctAbs =
                        pnl
                            .abs()
                            .divide(context.currentCapital, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal("100"))
                    if (pnlPctAbs >= percentLimit) {
                        val pnlPctScaled = pnlPctAbs.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        return blocked(
                            signalId,
                            RiskRule.DAILY_LOSS_LIMIT,
                            reason = "Daily loss $pnlPctScaled% reached percent limit -${percentLimit.toPlainString()}%",
                            context =
                                mapOf(
                                    "dailyRealizedPnl" to pnl.toPlainString(),
                                    "currentCapital" to context.currentCapital.toPlainString(),
                                    "pnlPercentAbs" to pnlPctAbs.toPlainString(),
                                    "limit" to percentLimit.toPlainString(),
                                    "limitType" to "percent",
                                ),
                        )
                    }
                }
            }
        }

        // 3. MAX_CONCURRENT_POSITIONS — BUY만 (SELL은 청산 → 통과)
        if (signal.side == SignalSide.BUY) {
            val currentCount = context.currentPositions.size
            if (currentCount >= context.config.maxConcurrentPositions) {
                return blocked(
                    signalId,
                    RiskRule.MAX_CONCURRENT_POSITIONS,
                    reason = "Concurrent positions $currentCount >= limit ${context.config.maxConcurrentPositions}",
                    context =
                        mapOf(
                            "currentCount" to currentCount,
                            "limit" to context.config.maxConcurrentPositions,
                            "codes" to context.currentPositions.map { it.code },
                        ),
                )
            }
        }

        if (signal.side == SignalSide.SELL) {
            val holdingQty = holdingQuantity(signal, context)
            if (holdingQty <= 0) {
                return blocked(
                    signalId,
                    RiskRule.SELL_WITHOUT_POSITION,
                    reason = "SELL signal has no current holding for code=${signal.code}",
                    context =
                        mapOf(
                            "code" to signal.code,
                            "currentCodes" to context.currentPositions.map { it.code },
                        ),
                )
            }
            val reservedSellQty = reservedSellQuantity(signal, context)
            val availableSellQty = (holdingQty - reservedSellQty).coerceAtLeast(0)
            if (availableSellQty <= 0) {
                return blocked(
                    signalId,
                    RiskRule.SELL_QUANTITY_UNAVAILABLE,
                    reason =
                        "SELL signal has no sellable quantity for code=${signal.code} " +
                            "(holding=$holdingQty, reservedSell=$reservedSellQty)",
                    context =
                        mapOf(
                            "code" to signal.code,
                            "holdingQty" to holdingQty,
                            "reservedSellQty" to reservedSellQty,
                            "availableSellQty" to availableSellQty,
                            "openOrderIds" to
                                context.openOrders
                                    .filter { it.code == signal.code && it.side == OrderSide.SELL }
                                    .mapNotNull { it.id },
                        ),
                )
            }
        }

        // 4. TRADE_FREQUENCY_MINUTE — D-1
        if (context.recentTradesMinute >= context.config.maxTradesPerMinute) {
            return blocked(
                signalId,
                RiskRule.TRADE_FREQUENCY_MINUTE,
                reason = "Strategy minute trade count ${context.recentTradesMinute} >= limit ${context.config.maxTradesPerMinute}",
                context =
                    mapOf(
                        "strategyId" to signal.strategyId,
                        "recentTradesMinute" to context.recentTradesMinute,
                        "limit" to context.config.maxTradesPerMinute,
                    ),
            )
        }

        // 5. TRADE_FREQUENCY_DAY — D-1
        if (context.recentTradesDay >= context.config.maxTradesPerDay) {
            return blocked(
                signalId,
                RiskRule.TRADE_FREQUENCY_DAY,
                reason = "Strategy daily trade count ${context.recentTradesDay} >= limit ${context.config.maxTradesPerDay}",
                context =
                    mapOf(
                        "strategyId" to signal.strategyId,
                        "recentTradesDay" to context.recentTradesDay,
                        "limit" to context.config.maxTradesPerDay,
                    ),
            )
        }

        // 6. POSITION_LIMIT — BUY 매수 한도=계좌 전체 공유 풀 기준 (사용자 결재 2026-06-16), BUY만 의미.
        //    매수가능 = min(총한도 − 전체노출(전 종목·전 전략 합), 예수금). 시그널 단위가격이 매수가능 초과 시 즉시 BLOCKED.
        if (signal.side == SignalSide.BUY) {
            val buyBudget = resolveBuyBudget(context)

            val unitCost = signal.priceAtSignal
            if (unitCost > buyBudget.buyable) {
                val reasonText =
                    "Unit price ${unitCost.toPlainString()} exceeds buyable amount " +
                        "${buyBudget.buyable.toPlainString()} " +
                        "(totalLimit=${buyBudget.totalLimit.toPlainString()}, totalExposure=${buyBudget.totalExposure.toPlainString()}, " +
                        "remainingLimit=${buyBudget.remainingLimit.toPlainString()}, orderableCash=${buyBudget.orderableCash.toPlainString()})"
                return blocked(
                    signalId,
                    RiskRule.POSITION_LIMIT,
                    reason = reasonText,
                    context =
                        mapOf(
                            "unitCost" to unitCost.toPlainString(),
                            "totalLimit" to buyBudget.totalLimit.toPlainString(),
                            "totalExposure" to buyBudget.totalExposure.toPlainString(),
                            "remainingLimit" to buyBudget.remainingLimit.toPlainString(),
                            "buyable" to buyBudget.buyable.toPlainString(),
                            "orderableCash" to buyBudget.orderableCash.toPlainString(),
                            "absLimit" to buyBudget.absoluteLimit.toPlainString(),
                            "percentLimit" to buyBudget.percentLimit.toPlainString(),
                            "currentCapital" to context.currentCapital.toPlainString(),
                        ),
                )
            }
        }

        log.debug(
            "RiskGatewayEvaluator ALLOWED: signalId={} userId={} strategyId={} side={} code={}",
            signalId,
            context.userId,
            signal.strategyId,
            signal.side,
            signal.code,
        )
        return RiskGatewayResult.Allowed(signalId)
    }

    /**
     * 공짜 룰 선차단 (canary D2 hotfix #5 — KIS rate limit 자가 압박 회피).
     *
     * 외부 KIS TR(체결 동기화·잔고 조회) 호출 **전에** I/O 0 인 룰만 먼저 평가해, 어차피 차단될 시그널이
     * 원장 TR 을 소모하지 않게 한다(어제 EGW00201 자가 기여 차단). 판정 **의미는 [evaluate]와 동일**(같은 룰·같은
     * short-circuit 순서) — 호출 순서만 앞당긴 것이다. 통과 시 caller 가 KIS 호출 후 [evaluate]로 전체 재평가한다.
     *
     * 평가 룰(공짜 입력만):
     *  1. [RiskRule.KILL_SWITCH_ON]            — killSwitch 포트(부재 시 fail-closed=ON 은 caller 책임)
     *  2. [RiskRule.MAX_CONCURRENT_POSITIONS]  — BUY 만, positions count
     *  3. [RiskRule.TRADE_FREQUENCY_MINUTE]
     *  4. [RiskRule.TRADE_FREQUENCY_DAY]
     *
     * 자본·체결 의존 룰(DAILY_LOSS·POSITION_LIMIT·SELL 잔고 가드)은 KIS 입력이 필요하므로 본 선차단 대상이 아니며
     * [evaluate] 전체 평가에서만 판정한다(의미 보존).
     */
    fun preCheckFreeRules(
        signal: Signal,
        killSwitchOn: Boolean,
        config: kr.spdaewoon.magicjar.domain.risk.RiskConfig,
        currentPositionCount: Int,
        currentCodes: List<String>,
        recentTradesMinute: Int,
        recentTradesDay: Int,
    ): RiskGatewayResult.Blocked? {
        val signalId = signal.id ?: error("Signal.id must be non-null for risk evaluation")

        if (killSwitchOn) {
            return blocked(
                signalId,
                RiskRule.KILL_SWITCH_ON,
                reason = "KillSwitch enabled for user=${signal.userId}",
                context = mapOf("userId" to signal.userId),
            )
        }

        if (signal.side == SignalSide.BUY && currentPositionCount >= config.maxConcurrentPositions) {
            return blocked(
                signalId,
                RiskRule.MAX_CONCURRENT_POSITIONS,
                reason = "Concurrent positions $currentPositionCount >= limit ${config.maxConcurrentPositions}",
                context =
                    mapOf(
                        "currentCount" to currentPositionCount,
                        "limit" to config.maxConcurrentPositions,
                        "codes" to currentCodes,
                    ),
            )
        }

        if (recentTradesMinute >= config.maxTradesPerMinute) {
            return blocked(
                signalId,
                RiskRule.TRADE_FREQUENCY_MINUTE,
                reason = "Strategy minute trade count $recentTradesMinute >= limit ${config.maxTradesPerMinute}",
                context =
                    mapOf(
                        "strategyId" to signal.strategyId,
                        "recentTradesMinute" to recentTradesMinute,
                        "limit" to config.maxTradesPerMinute,
                    ),
            )
        }

        if (recentTradesDay >= config.maxTradesPerDay) {
            return blocked(
                signalId,
                RiskRule.TRADE_FREQUENCY_DAY,
                reason = "Strategy daily trade count $recentTradesDay >= limit ${config.maxTradesPerDay}",
                context =
                    mapOf(
                        "strategyId" to signal.strategyId,
                        "recentTradesDay" to recentTradesDay,
                        "limit" to config.maxTradesPerDay,
                    ),
            )
        }

        return null
    }

    fun resolveBuyQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int {
        val buyBudget = resolveBuyBudget(context)
        return buyBudget.buyable
            .divide(signal.priceAtSignal, 0, RoundingMode.DOWN)
            .toInt()
            .coerceAtLeast(0)
    }

    fun resolveSellQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int = (holdingQuantity(signal, context) - reservedSellQuantity(signal, context)).coerceAtLeast(0)

    /**
     * BUY 매수 버짓 산정 — **계좌 전체 공유 풀 + 예수금 cap** (사용자 결재 2026-06-16).
     *
     * ## 왜 종목별 풀이 아니라 계좌 전체 공유 풀인가
     * 기존(PR #1008 이전)엔 노출을 **그 종목만**(`currentCodeExposure` = filter code==signal.code) 차감해, 종목마다
     * 한도(maxPositionAmount)가 따로 걸렸다. 사용자 의도는 **"계좌 전체가 공유하는 1개 풀"** 이다. 예) 순자산 1억·
     * 한도 1,000만에서 #1 전략이 900만 매수하면, #2 전략은 **남은 100만만** 매수할 수 있어야 한다. 따라서 노출은
     * 전 종목·전 전략 합산([totalExposure])으로 깎는다.
     *
     * ## 비율 한도 복원 (PR #1008 회귀 정정)
     * PR #1008 이 비율 한도(maxPositionPercent)를 제거했으나, 원래 A-1 규약은 **min(절대, 비율)** 이다. 복원한다.
     * - 총한도 = min(maxPositionAmount(절대, 원), currentCapital(순자산) × maxPositionPercent / 100).
     * - 비율 기준 자본 = **순자산(currentCapital)** — 사용자 결재.
     *
     * ## 예수금 cap (PR #1008 정합 유지)
     * 보유주식이 많아 순자산은 크지만 실제 주문할 현금(예수금)이 부족하면 KIS 가 "주문가능금액 초과(EGW...)"로 통째
     * 거부한다. 따라서 최종 매수가능은 예수금([RiskContext.orderableCash] = KIS `prvs_rcdl_excc_amt` D+2 실가용,
     * 비정상 시 예수금총액 fallback — consumer 배선, 0 합성 금지)으로 한 번 더 cap 한다.
     *
     * ## 최종 공식
     * ```
     * 총한도   = min( maxPositionAmount,  currentCapital × maxPositionPercent / 100 )
     * 전체노출 = Σ over currentPositions { avgPrice × quantity }   ← 전 종목·전 전략, 매수원가 기준
     * 남은한도 = (총한도 − 전체노출).coerceAtLeast(0)
     * 매수가능 = min( 남은한도,  orderableCash )
     * ```
     * 예수금은 노출을 다시 차감하지 않는다(이미 현금 잔액 — 보유는 이미 현금에서 빠진 값). 남은한도와 예수금 중
     * 작은 값이 매수가능이다.
     */
    private fun resolveBuyBudget(context: RiskContext): RiskBudgetCalculator.BuyBudget =
        // 사이징·사용량 표시의 단일 진실원 — domain RiskBudgetCalculator 로 위임(api RiskUsageService 와 동일 산식 보장).
        RiskBudgetCalculator.resolveBuyBudget(
            config = context.config,
            currentCapital = context.currentCapital,
            orderableCash = context.orderableCash,
            positions = context.currentPositions,
        )

    /**
     * SELL 가용 수량 평가용 보유 수량 (ADR 0110 §2-4 SELL 전략별 분기).
     *
     * - `context.strategyPosition != null`(분할매수 전략 lot 존재) → **그 전략 보유 수량**(다전략 교차 청산 방지).
     * - null(분할매수 미사용 전략·과거 보유) → 기존 user+code 합산 보유(하위호환 fallback — zero-regression).
     *
     * ## 원장 0 → 실잔고 fallback (canary D2 hotfix #4 — D1 잔여)
     * 과거엔 strategyPosition 행이 **존재하면 quantity=0 이어도 그 0을 채택**해 실잔고(currentPositions) fallback 을
     * 못 탔다. strategy_position 원장이 오염(체결 누락·replay 미스)돼 quantity=0 으로 떨어지면, 실제로는 KIS 잔고에
     * 보유가 있는데도 SELL 이 `SELL_WITHOUT_POSITION` 으로 영구 차단 → 청산 마비가 재발한다. `quantity > 0` 조건을
     * takeIf 에 추가해, 원장 0 이면 user+code 실잔고 합산을 최후 방어선으로 사용한다(분할매수 정상 lot 은 영향 0).
     */
    private fun holdingQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int =
        context.strategyPosition
            ?.takeIf { it.code == signal.code && it.strategyId == signal.strategyId && it.quantity > 0 }
            ?.quantity
            ?: context.currentPositions
                .firstOrNull { it.code == signal.code && it.quantity > 0 }
                ?.quantity
            ?: 0

    /**
     * 미체결 SELL 예약 수량 (ADR 0110 §2-4 + 자본 path hotfix — "먼저 오는 쪽이 이긴다").
     *
     * - 전략 lot 기준 평가 시(`strategyPosition != null`) → **그 전략의** open SELL 만 합산(교차 차감 방지).
     * - null fallback → 기존 user+code 전체 open SELL 합산(하위호환).
     *
     * ## auto-tp/auto-sl 선주문 제외 (hotfix — 매도 시그널 무력화 방지)
     * 우리가 건 `auto-tp:`/`auto-sl:` 같은 전략 AUTO 선주문은 매도 시그널/손절 발행 직전 [RestingSellOrderCanceller]가
     * **선취소**해 수량을 풀어준다. 따라서 이 자가 관리 SELL은 예약 수량에서 제외해야 새 청산 시그널이 ALLOWED 된다
     * (제외하지 않으면 익절 선주문이 보유 전량을 예약 → availableSell=0 → 매도 시그널이 SELL_QUANTITY_UNAVAILABLE로
     * 무력화되던 결함). 실제 KIS 매도가능수량은 [SignalGeneratedKafkaListener.ensureKisHoldingIfSell] + KIS 자체가 보증.
     * 수동(MANUAL)/타전략 open SELL은 그대로 합산(침범 금지 — 취소 대상 아님).
     */
    private fun reservedSellQuantity(
        signal: Signal,
        context: RiskContext,
    ): Int {
        val byStrategy = context.strategyPosition?.let { it.code == signal.code && it.strategyId == signal.strategyId } == true
        return context.openOrders
            .filter { it.code == signal.code && it.side == OrderSide.SELL }
            .filter { !byStrategy || it.strategyId == signal.strategyId }
            .filterNot { it.isCancellableAutoExit(signal.strategyId) }
            .sumOf { it.quantity }
    }

    private fun blocked(
        signalId: Long,
        rule: RiskRule,
        reason: String,
        context: Map<String, Any?>,
    ): RiskGatewayResult.Blocked {
        log.info("RiskGatewayEvaluator BLOCKED: signalId={} rule={} reason={}", signalId, rule, reason)
        return RiskGatewayResult.Blocked(signalId, rule, reason, context)
    }
}
