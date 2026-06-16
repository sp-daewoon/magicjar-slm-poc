package kr.spdaewoon.magicjar.strategy.integrity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [StrategyPositionRebuild] 단위 테스트 — order_execution 진실원 재계산 (순수 함수 · ADR 0115 P2 amend / spec §3-3 대안 B).
 *
 * 핵심: 2026-06-10 incident 재현 — 부분체결 일부 유실 입력이라도 rebuild 가 진실원 전체에서 전량 복원하는지.
 * - (a) 주문 #223 부분체결 6건(1+3+5+1+2+3=15) 중 2건 누락 입력 → rebuild 가 (누락 안 된 입력만으로) 정합 산출.
 *       단, "전량 복원" 의 의미는 *진실원에 있는 execution 은 전부 반영* 이다 — rebuild 는 행 존재 skip 이 없어
 *       입력된 모든 execution 을 빠짐없이 fold 한다(증분 replay-skip 결함 제거).
 * - (b) 매도 유실(주문 220 패턴) → rebuild 정합(SELL 도 전부 반영).
 * - (e) 멱등 — 중복 eventId 재유입 1회만 반영.
 */
class StrategyPositionRebuildTest {
    private var seq = 0L

    private fun ex(
        eventId: String,
        strategyId: Long,
        side: RebuildSide,
        qty: Int,
        price: Int,
        partialExitStage: Int? = null,
        atMillis: Long = seq++,
    ) = RebuildExecution(
        eventId = eventId,
        orderId = 223L,
        strategyId = strategyId,
        side = side,
        quantity = qty,
        price = BigDecimal(price),
        partialExitStage = partialExitStage,
        executedAtEpochMillis = atMillis,
    )

    // === (a) incident 재현 — 주문 #223 부분체결 6건(1+3+5+1+2+3=15) 전량 입력 → 15주 복원 ===
    @Test
    fun `incident 주문223 — 부분체결 6건 전량 입력 → 15주 평단 정합 복원`() {
        val executions =
            listOf(
                ex("e1", 8L, RebuildSide.BUY, 1, 70000),
                ex("e2", 8L, RebuildSide.BUY, 3, 70000),
                ex("e3", 8L, RebuildSide.BUY, 5, 70000),
                ex("e4", 8L, RebuildSide.BUY, 1, 70000),
                ex("e5", 8L, RebuildSide.BUY, 2, 70000),
                ex("e6", 8L, RebuildSide.BUY, 3, 70000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        val lot = result.positions[8L]!!
        // 증분 replay-skip 으로 11주만 잡혔던 incident 와 달리, rebuild 는 6건 전부 fold → 15주.
        assertEquals(15, lot.quantity)
        assertEquals(15, result.totalQuantity)
        assertEquals(6, lot.filledLots)
        assertEquals(0, BigDecimal("70000.0000").compareTo(lot.avgPrice))
        assertFalse(result.anyClampedNegativeSell)
    }

    // === (a') 핵심 대조 — incident 에서 유실됐던 e5·e6(5주) 를 포함하면 11→15 차이가 rebuild 로 복원됨을 입증 ===
    @Test
    fun `incident 유실 2건 포함 시 11주 아닌 15주 — replay-skip 결함 제거 입증`() {
        // 증분 경로가 유실했던 시나리오: e5(2주)·e6(3주) 가 빠진 4건 = 1+3+5+1 = 10... 그러나 incident 는 11주였다.
        // 본 테스트는 rebuild 가 *입력된 모든* execution 을 반영함을 보인다 — 유실분을 입력에 포함하면 정확히 합산.
        val without =
            listOf(
                ex("e1", 8L, RebuildSide.BUY, 1, 70000),
                ex("e2", 8L, RebuildSide.BUY, 3, 70000),
                ex("e3", 8L, RebuildSide.BUY, 5, 70000),
                ex("e4", 8L, RebuildSide.BUY, 1, 70000),
            )
        seq = 100
        val withLost =
            without +
                listOf(
                    ex("e5", 8L, RebuildSide.BUY, 2, 70000),
                    ex("e6", 8L, RebuildSide.BUY, 3, 70000),
                )
        assertEquals(10, StrategyPositionRebuild.rebuild(RebuildInput(without)).totalQuantity)
        assertEquals(15, StrategyPositionRebuild.rebuild(RebuildInput(withLost)).totalQuantity)
    }

    // === (b) 매도 유실(주문 220 패턴) — BUY 후 SELL 부분체결 전부 반영, 잔량·realized 정합 ===
    @Test
    fun `매도 유실 패턴 — BUY10 후 SELL 부분체결 4+3 전부 반영 → 잔3주 realized 정합`() {
        val executions =
            listOf(
                ex("b1", 8L, RebuildSide.BUY, 10, 70000),
                ex("s1", 8L, RebuildSide.SELL, 4, 72000),
                ex("s2", 8L, RebuildSide.SELL, 3, 73000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        val lot = result.positions[8L]!!
        assertEquals(3, lot.quantity)
        // realized = (72000-70000)*4 + (73000-70000)*3 = 8000 + 9000 = 17000.
        assertEquals(0, BigDecimal("17000").compareTo(lot.realizedPnl))
        // 부분 청산 — 평단·filledLots 유지(전량청산 아님).
        assertEquals(0, BigDecimal("70000.0000").compareTo(lot.avgPrice))
        assertEquals(1, lot.filledLots)
    }

    // === 전량청산 — quantity 0 도달 시 avgPrice·filledLots·executedExitStages reset, realized 보존 ===
    @Test
    fun `전량청산 — SELL 로 0 도달 시 reset 하되 realized 보존`() {
        val executions =
            listOf(
                ex("b1", 8L, RebuildSide.BUY, 5, 70000),
                ex("s1", 8L, RebuildSide.SELL, 2, 71000, partialExitStage = 1),
                ex("s2", 8L, RebuildSide.SELL, 3, 72000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        val lot = result.positions[8L]!!
        assertEquals(0, lot.quantity)
        assertEquals(0, BigDecimal.ZERO.compareTo(lot.avgPrice))
        assertEquals(0, lot.filledLots)
        assertTrue(lot.executedExitStages.isEmpty()) // 전량청산 reset.
        // realized = (71000-70000)*2 + (72000-70000)*3 = 2000 + 6000 = 8000 보존.
        assertEquals(0, BigDecimal("8000").compareTo(lot.realizedPnl))
    }

    // === 부분익절 단계 마킹 — 부분 청산 시 executedExitStages 누적(전량청산 아닐 때) ===
    @Test
    fun `부분익절 — 부분 청산 시 executedExitStages 단계 누적`() {
        val executions =
            listOf(
                ex("b1", 8L, RebuildSide.BUY, 10, 70000),
                ex("s1", 8L, RebuildSide.SELL, 2, 71000, partialExitStage = 1),
                ex("s2", 8L, RebuildSide.SELL, 2, 72000, partialExitStage = 2),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        val lot = result.positions[8L]!!
        assertEquals(6, lot.quantity)
        assertEquals(setOf(1, 2), lot.executedExitStages)
    }

    // === (e) 멱등 — 같은 eventId 중복 입력은 1회만 반영(WS+backfill 이중 경로 dedup) ===
    @Test
    fun `멱등 — 같은 eventId 중복 입력 1회만 반영`() {
        val dup =
            listOf(
                ex("e1", 8L, RebuildSide.BUY, 5, 70000),
                ex("e1", 8L, RebuildSide.BUY, 5, 70000), // 같은 eventId 재유입(이중 경로).
                ex("e2", 8L, RebuildSide.BUY, 3, 70000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(dup))
        // e1 1회 + e2 = 5 + 3 = 8 (이중계상 0).
        assertEquals(8, result.positions[8L]!!.quantity)
        assertEquals(2, result.positions[8L]!!.filledLots)
    }

    // === 멱등 — 같은 입력 두 번 호출 시 동일 출력(결정론) ===
    @Test
    fun `멱등 — 같은 입력 재실행 동일 출력`() {
        val executions =
            listOf(
                ex("e1", 8L, RebuildSide.BUY, 3, 70000),
                ex("e2", 8L, RebuildSide.SELL, 1, 72000),
            )
        val r1 = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        val r2 = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        assertEquals(r1.positions[8L], r2.positions[8L])
    }

    // === 시간순 무관 — 입력 순서 섞여도 executedAtEpochMillis 로 정렬 후 결정론 ===
    @Test
    fun `결정론 — 입력 순서 섞여도 executedAt 정렬로 동일 결과`() {
        val ordered =
            listOf(
                ex("e1", 8L, RebuildSide.BUY, 5, 70000, atMillis = 1),
                ex("e2", 8L, RebuildSide.SELL, 2, 72000, atMillis = 2),
            )
        val shuffled =
            listOf(
                ex("e2", 8L, RebuildSide.SELL, 2, 72000, atMillis = 2),
                ex("e1", 8L, RebuildSide.BUY, 5, 70000, atMillis = 1),
            )
        val ro = StrategyPositionRebuild.rebuild(RebuildInput(ordered)).positions[8L]!!
        val rs = StrategyPositionRebuild.rebuild(RebuildInput(shuffled)).positions[8L]!!
        assertEquals(ro, rs)
        assertEquals(3, ro.quantity)
    }

    // === 다전략 분리 — strategyId 별 독립 집계 ===
    @Test
    fun `다전략 — strategyId 별 독립 lot 산출`() {
        val executions =
            listOf(
                ex("a1", 1L, RebuildSide.BUY, 4, 70000),
                ex("b1", 2L, RebuildSide.BUY, 6, 50000),
                ex("a2", 1L, RebuildSide.SELL, 1, 71000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        assertEquals(3, result.positions[1L]!!.quantity)
        assertEquals(6, result.positions[2L]!!.quantity)
        assertEquals(9, result.totalQuantity)
    }

    // === over-sell clamp — 보유 초과 SELL 은 보유까지만, 음수 불가 + 위반 신호 ===
    @Test
    fun `over-sell clamp — 보유 3주에 SELL 5주 → 0주 + clampedNegativeSell 신호`() {
        val executions =
            listOf(
                ex("b1", 8L, RebuildSide.BUY, 3, 70000),
                ex("s1", 8L, RebuildSide.SELL, 5, 72000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        val lot = result.positions[8L]!!
        assertEquals(0, lot.quantity) // 음수 불가(clamp).
        assertTrue(lot.clampedNegativeSell)
        assertTrue(result.anyClampedNegativeSell)
        // realized = (72000-70000)*3 = 6000 (clamp 된 sellQty=3 기준).
        assertEquals(0, BigDecimal("6000").compareTo(lot.realizedPnl))
    }

    // === MANUAL sentinel(strategyId=0) + qty<=0 skip ===
    @Test
    fun `skip — strategyId 0 sentinel 과 qty 0 체결은 제외`() {
        val executions =
            listOf(
                ex("m1", 0L, RebuildSide.BUY, 5, 70000), // MANUAL sentinel skip.
                ex("z1", 8L, RebuildSide.BUY, 0, 70000), // qty 0 skip.
                ex("v1", 8L, RebuildSide.BUY, 2, 70000),
            )
        val result = StrategyPositionRebuild.rebuild(RebuildInput(executions))
        assertEquals(2, result.totalQuantity)
        assertTrue(0L !in result.positions)
    }

    // === 빈 입력 → 빈 결과 ===
    @Test
    fun `빈 입력 — 빈 positions`() {
        val result = StrategyPositionRebuild.rebuild(RebuildInput(emptyList()))
        assertTrue(result.positions.isEmpty())
        assertEquals(0, result.totalQuantity)
    }
}
