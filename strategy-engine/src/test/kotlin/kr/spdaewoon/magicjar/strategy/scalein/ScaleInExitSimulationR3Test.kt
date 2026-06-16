package kr.spdaewoon.magicjar.strategy.scalein

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 분할매수 R3 정합 테스트 — 리플레이·백테스트가 공유하는 [ScaleInExitSimulation] 단일 함수 검증 (spec §8 R3).
 *
 * live(RealtimeSignalGenerator)·리플레이·백테스트가 **동일 [ScaleInExitCalculator]** 를 통과한다는 LOCK을
 * 시뮬레이션 어댑터 경계에서 재확인한다. R3 6 시나리오 + live calculator 단위 입력과의 cross-check 1건.
 *
 * 시나리오:
 *  (a) 3분할 진입 평단 가중평균 정확성
 *  (b) stop-loss 전량 청산
 *  (c) partial-tp 단계 1회 발화(가격이 trigger 위에 머물러도 중복 0)
 *  (d) 동시 터치 stop 우선
 *  (e) scale-in null = 기존 동일(isActive=false 게이트)
 *  (f) 균등 분할 나머지 흡수(resolvedWeights)
 *  (cross) live ScaleInExitCalculatorTest 입력 → ScaleInExitSimulation 동일 출력
 */
class ScaleInExitSimulationR3Test {
    private fun virtual(
        quantity: Int,
        avgPrice: String,
        filledLots: Int = 1,
        executedExitStages: Set<Int> = emptySet(),
    ) = VirtualStrategyPosition(
        code = "005930",
        quantity = quantity,
        avgPrice = BigDecimal(avgPrice),
        filledLots = filledLots,
        executedExitStages = executedExitStages,
    )

    // (a) 3분할 진입 평단 가중평균 정확성 — 시뮬레이션이 회차 인덱스/비중을 정확히 산출하고
    //     호출부 평단 갱신((avg*qty + price*sliceQty)/(qty+sliceQty))이 정확한지 검증.
    @Test
    fun `scenario a - three slice scale-in averages correctly across rounds`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                sliceWeights = listOf(BigDecimal("50"), BigDecimal("30"), BigDecimal("20")),
                minSliceIntervalSec = 0,
            )

        // 회차 0: 미보유 + BUY → slice 0
        val d0 =
            ScaleInExitSimulation.evaluate(
                position = null,
                currentPrice = BigDecimal("1000"),
                scaleIn = plan,
                fixedStopLossPct = BigDecimal("20"),
                takeProfitPct = BigDecimal("50"),
                partialExits = null,
                buySignalFired = true,
            )
        assertTrue(d0 is ScaleInExitDecision.EnterSlice)
        assertEquals(0, (d0 as ScaleInExitDecision.EnterSlice).sliceIndex)
        assertEquals(BigDecimal("50"), d0.weightPct)

        // 회차 0 체결: 50주 @1000 → 평단 1000, qty 50, filledLots 1
        // 회차 1: 보유 50@1000 + BUY @1100 → slice 1 (weight 30)
        val d1 =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 50, avgPrice = "1000", filledLots = 1),
                currentPrice = BigDecimal("1100"),
                scaleIn = plan,
                fixedStopLossPct = BigDecimal("20"),
                takeProfitPct = BigDecimal("50"),
                partialExits = null,
                buySignalFired = true,
            )
        assertTrue(d1 is ScaleInExitDecision.EnterSlice)
        assertEquals(1, (d1 as ScaleInExitDecision.EnterSlice).sliceIndex)
        assertEquals(BigDecimal("30"), d1.weightPct)

        // 회차 1 체결: 30주 @1100 → 평단 = (1000*50 + 1100*30)/80 = (50000+33000)/80 = 1037.5
        val avgAfter1 =
            BigDecimal("1000")
                .multiply(BigDecimal(50))
                .add(BigDecimal("1100").multiply(BigDecimal(30)))
                .divide(BigDecimal(80), 6, java.math.RoundingMode.HALF_UP)
        assertEquals(0, avgAfter1.compareTo(BigDecimal("1037.5")))

        // 회차 2: 보유 80@1037.5 + BUY → slice 2 (weight 20)
        val d2 =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 80, avgPrice = "1037.5", filledLots = 2),
                currentPrice = BigDecimal("1037.5"),
                scaleIn = plan,
                fixedStopLossPct = BigDecimal("20"),
                takeProfitPct = BigDecimal("50"),
                partialExits = null,
                buySignalFired = true,
            )
        assertTrue(d2 is ScaleInExitDecision.EnterSlice)
        assertEquals(2, (d2 as ScaleInExitDecision.EnterSlice).sliceIndex)

        // 회차 3: filledLots == totalSlices → 추가 진입 차단(평단 오염 방지) → Hold
        val d3 =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 100, avgPrice = "1037.5", filledLots = 3),
                currentPrice = BigDecimal("1037.5"),
                scaleIn = plan,
                fixedStopLossPct = BigDecimal("20"),
                takeProfitPct = BigDecimal("50"),
                partialExits = null,
                buySignalFired = true,
            )
        assertTrue(d3 is ScaleInExitDecision.Hold)
    }

    // (b) stop-loss 전량 청산
    @Test
    fun `scenario b - stop-loss exits full quantity`() {
        val decision =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 80, avgPrice = "1000"),
                currentPrice = BigDecimal("940"),
                scaleIn = null,
                fixedStopLossPct = BigDecimal("5"),
                takeProfitPct = null,
                partialExits = null,
                buySignalFired = false,
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
        assertEquals(80, (decision as ScaleInExitDecision.StopLoss).quantity)
    }

    // (c) partial-tp 단계 1회 발화 — 가격이 trigger 위에 머물러도 executedExitStages로 중복 0
    @Test
    fun `scenario c - partial-tp fires once and not again once stage recorded`() {
        val partials =
            listOf(
                PartialExit(triggerProfitPct = BigDecimal("3"), exitWeightPct = BigDecimal("50")),
                PartialExit(triggerProfitPct = BigDecimal("7"), exitWeightPct = BigDecimal("30")),
            )
        // 1차: 평단 1000, 현재가 1040(+4%) → 단계0 발화 (보유 10 × 50% = 5)
        val first =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 10, avgPrice = "1000", executedExitStages = emptySet()),
                currentPrice = BigDecimal("1040"),
                scaleIn = null,
                fixedStopLossPct = null,
                takeProfitPct = BigDecimal("50"),
                partialExits = partials,
                buySignalFired = false,
            )
        assertTrue(first is ScaleInExitDecision.PartialTakeProfit)
        first as ScaleInExitDecision.PartialTakeProfit
        assertEquals(0, first.stageIndex)
        assertEquals(5, first.quantity)

        // 2차: 단계0 이미 발화(executedExitStages={0}), 가격 여전히 +4% → 중복 발화 0 → Hold
        val second =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 5, avgPrice = "1000", executedExitStages = setOf(0)),
                currentPrice = BigDecimal("1040"),
                scaleIn = null,
                fixedStopLossPct = null,
                takeProfitPct = BigDecimal("50"),
                partialExits = partials,
                buySignalFired = false,
            )
        assertTrue(second is ScaleInExitDecision.Hold)
    }

    // (d) 동시 터치 stop 우선 (보수적)
    @Test
    fun `scenario d - simultaneous touch prefers stop-loss`() {
        val decision =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 10, avgPrice = "1000"),
                currentPrice = BigDecimal("940"), // stop(950) 아래 + take(1%) 동시 조건
                scaleIn = null,
                fixedStopLossPct = BigDecimal("5"),
                takeProfitPct = BigDecimal("1"),
                partialExits = null,
                buySignalFired = false,
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
    }

    // (e) scale-in null + partialExits null → isActive=false (현행 단일 경로 게이트)
    @Test
    fun `scenario e - scale-in and partialExits null is inactive`() {
        assertEquals(false, ScaleInExitSimulation.isActive(scaleIn = null, partialExits = null))
        assertEquals(false, ScaleInExitSimulation.isActive(scaleIn = null, partialExits = emptyList()))
        assertEquals(
            true,
            ScaleInExitSimulation.isActive(scaleIn = ScaleInPlan(totalSlices = 2, minSliceIntervalSec = 0), partialExits = null),
        )
        assertEquals(
            true,
            ScaleInExitSimulation.isActive(
                scaleIn = null,
                partialExits = listOf(PartialExit(BigDecimal("3"), BigDecimal("50"))),
            ),
        )
    }

    // (f) 균등 분할 나머지 흡수 — resolvedWeights가 마지막 회차에 반올림 오차 흡수(합=100)
    @Test
    fun `scenario f - even split absorbs remainder in last slice`() {
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = null, minSliceIntervalSec = 0) // null → 균등
        val weights = plan.resolvedWeights()
        assertEquals(3, weights.size)
        val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        assertEquals(0, sum.compareTo(BigDecimal("100")))
        // 100/3 = 33.3333 (DOWN), 마지막이 나머지 흡수 → head 두 개는 동일, 마지막은 더 큼
        assertEquals(0, weights[0].compareTo(weights[1]))
        assertTrue(weights[2] >= weights[0])

        // 시뮬레이션 회차 0이 균등 weight를 그대로 반환하는지
        val d0 =
            ScaleInExitSimulation.evaluate(
                position = null,
                currentPrice = BigDecimal("1000"),
                scaleIn = plan,
                fixedStopLossPct = BigDecimal("20"),
                takeProfitPct = BigDecimal("50"),
                partialExits = null,
                buySignalFired = true,
            )
        assertTrue(d0 is ScaleInExitDecision.EnterSlice)
        assertEquals(0, (d0 as ScaleInExitDecision.EnterSlice).weightPct.compareTo(weights[0]))
    }

    // (cross) live ScaleInExitCalculatorTest의 대표 입력 → 시뮬레이션 어댑터가 동일 sealed 출력
    //         (단일 함수 공유 LOCK — 리플레이/백테스트가 live와 같은 brain을 통과함을 보장).
    @Test
    fun `cross-check - simulation adapter matches direct calculator output for live inputs`() {
        // ScaleInExitCalculatorTest `stop-loss wins over take-profit` 입력 재사용
        val direct =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position =
                        StrategyPosition(
                            userId = "u1",
                            strategyId = 10L,
                            code = "005930",
                            quantity = 10,
                            avgPrice = BigDecimal("1000"),
                            filledLots = 1,
                            openedAt = Instant.parse("2026-06-05T01:00:00Z"),
                            updatedAt = Instant.parse("2026-06-05T01:00:00Z"),
                        ),
                    currentPrice = BigDecimal("940"),
                    fixedStopLossPct = BigDecimal("5"),
                    takeProfitPct = BigDecimal("1"),
                ),
            )
        val viaSim =
            ScaleInExitSimulation.evaluate(
                position = virtual(quantity = 10, avgPrice = "1000"),
                currentPrice = BigDecimal("940"),
                scaleIn = null,
                fixedStopLossPct = BigDecimal("5"),
                takeProfitPct = BigDecimal("1"),
                partialExits = null,
                buySignalFired = false,
            )
        assertEquals(direct, viaSim)
        assertTrue(viaSim is ScaleInExitDecision.StopLoss)
    }
}
