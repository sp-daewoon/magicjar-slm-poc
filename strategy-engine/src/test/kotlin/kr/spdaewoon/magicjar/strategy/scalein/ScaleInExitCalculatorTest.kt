package kr.spdaewoon.magicjar.strategy.scalein

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [ScaleInExitCalculator] 핵심 분기 단위 테스트 (순수 함수 — 결정론·저비용).
 *
 * 우선순위 LOCK(spec §3-3): STOP_LOSS > PARTIAL_TP > TAKE_PROFIT > ENTER_SLICE > HOLD.
 */
class ScaleInExitCalculatorTest {
    private val now = Instant.parse("2026-06-05T01:00:00Z")

    private fun position(
        quantity: Int,
        avgPrice: String,
        filledLots: Int = 1,
        executedExitStages: Set<Int> = emptySet(),
    ) = StrategyPosition(
        userId = "u1",
        strategyId = 10L,
        code = "005930",
        quantity = quantity,
        avgPrice = BigDecimal(avgPrice),
        filledLots = filledLots,
        executedExitStages = executedExitStages,
        openedAt = now,
        updatedAt = now,
    )

    @Test
    fun `stop-loss wins over take-profit on simultaneous touch`() {
        // 평단 1000, stop 5%(=950), take 1%. 현재가 940 → stop·take 동시 조건이지만 stop 우선
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000"),
                    currentPrice = BigDecimal("940"),
                    fixedStopLossPct = BigDecimal("5"),
                    takeProfitPct = BigDecimal("1"),
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
        assertEquals(10, (decision as ScaleInExitDecision.StopLoss).quantity)
    }

    @Test
    fun `partial take-profit fires only unexecuted stage and picks highest reached trigger`() {
        // 단계0: trigger 3% weight 50, 단계1: trigger 7% weight 30. 평단 1000, 현재가 1080 → +8% → 둘 다 도달
        // 가장 높은 trigger(단계1) 선택. 보유 10 × 30% = 3
        val partials =
            listOf(
                PartialExit(triggerProfitPct = BigDecimal("3"), exitWeightPct = BigDecimal("50")),
                PartialExit(triggerProfitPct = BigDecimal("7"), exitWeightPct = BigDecimal("30")),
            )
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000"),
                    currentPrice = BigDecimal("1080"),
                    takeProfitPct = BigDecimal("50"), // 전량 익절 미도달
                    partialExits = partials,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.PartialTakeProfit)
        decision as ScaleInExitDecision.PartialTakeProfit
        assertEquals(1, decision.stageIndex)
        assertEquals(3, decision.quantity)
    }

    @Test
    fun `executed partial stage is not re-fired`() {
        // 단계0 이미 발화(executedExitStages={0}). 현재가 +4% → 단계0만 도달했으나 이미 발화 → HOLD
        val partials = listOf(PartialExit(triggerProfitPct = BigDecimal("3"), exitWeightPct = BigDecimal("50")))
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", executedExitStages = setOf(0)),
                    currentPrice = BigDecimal("1040"),
                    partialExits = partials,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `take-profit sells remaining quantity`() {
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 7, avgPrice = "1000"),
                    currentPrice = BigDecimal("1100"),
                    takeProfitPct = BigDecimal("5"),
                ),
            )
        assertTrue(decision is ScaleInExitDecision.TakeProfit)
        assertEquals(7, (decision as ScaleInExitDecision.TakeProfit).quantity)
    }

    @Test
    fun `scale-in next slice enters on buy signal when filled lots below total`() {
        // 3분할, 1회 체결됨(filledLots=1). 다음 회차(index 1) 진입. 균등 분할 weight
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("1005"), // 익절·손절 미도달
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("10"),
                    takeProfitPct = BigDecimal("20"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.EnterSlice)
        assertEquals(1, (decision as ScaleInExitDecision.EnterSlice).sliceIndex)
    }

    @Test
    fun `scale-in ignored when slices complete`() {
        val plan = ScaleInPlan(totalSlices = 2, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000", filledLots = 2),
                    currentPrice = BigDecimal("1005"),
                    scaleIn = plan,
                    takeProfitPct = BigDecimal("20"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `price-drop slice requires sufficient drop from last fill`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                reentryTrigger = ReentryTrigger.PRICE_DROP_PCT,
                priceDropPct = BigDecimal("-3.0"),
                minSliceIntervalSec = 0,
            )
        // 직전 체결가 1000, 현재가 995 (-0.5%) → -3% 미달 → 진입 없음(HOLD)
        val notEnough =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("995"),
                    scaleIn = plan,
                    takeProfitPct = BigDecimal("20"),
                    fixedStopLossPct = BigDecimal("50"),
                    buySignalFired = true,
                    lastSliceFillPrice = BigDecimal("1000"),
                ),
            )
        assertTrue(notEnough is ScaleInExitDecision.Hold)

        // 현재가 960 (-4%) → -3% 초과 하락 → 진입
        val enough =
            notEnough.let {
                ScaleInExitCalculator.evaluate(
                    ScaleInExitInput(
                        position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                        currentPrice = BigDecimal("960"),
                        scaleIn = plan,
                        takeProfitPct = BigDecimal("20"),
                        fixedStopLossPct = BigDecimal("50"),
                        buySignalFired = true,
                        lastSliceFillPrice = BigDecimal("1000"),
                    ),
                )
            }
        assertTrue(enough is ScaleInExitDecision.EnterSlice)
    }

    @Test
    fun `min slice interval blocks too-soon reentry`() {
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 60)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("1000"),
                    scaleIn = plan,
                    takeProfitPct = BigDecimal("20"),
                    fixedStopLossPct = BigDecimal("50"),
                    buySignalFired = true,
                    lastSliceEpochSec = 1_000L,
                    nowEpochSec = 1_030L, // 30s < 60s
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `no position with buy signal and null scaleIn enters single slice`() {
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = null,
                    currentPrice = BigDecimal("1000"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.EnterSlice)
        assertEquals(0, (decision as ScaleInExitDecision.EnterSlice).sliceIndex)
    }

    @Test
    fun `hold when no buy signal and no exit condition`() {
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000"),
                    currentPrice = BigDecimal("1005"),
                    takeProfitPct = BigDecimal("20"),
                    fixedStopLossPct = BigDecimal("10"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }
}
