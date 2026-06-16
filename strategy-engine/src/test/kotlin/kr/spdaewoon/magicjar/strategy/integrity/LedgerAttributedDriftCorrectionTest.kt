package kr.spdaewoon.magicjar.strategy.integrity

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * [LedgerAttributedDriftCorrection] 단위 테스트 — 부족 방향 귀속 보정 (순수 함수 · ADR 0115 §P1-1 amend 2026-06-10).
 *
 * 규칙 LOCK: 부족만(>= NoChange) / 귀속 ledger 로 authority 정확 재구성 시만 Adjust / 모호(합≠authority) WARN /
 * 멱등(절대 목표 set) / 토글 off Disabled / leg 부재 신규 생성 / over-count 0.
 */
class LedgerAttributedDriftCorrectionTest {
    private val t0 = Instant.parse("2026-06-10T01:00:00Z")

    private fun leg(
        strategyId: Long,
        quantity: Int,
    ) = StrategyPositionLeg(strategyId = strategyId, quantity = quantity, updatedAt = t0)

    private fun input(
        authority: Int,
        legs: List<StrategyPositionLeg>,
        attributions: List<LedgerAttribution>,
        enabled: Boolean = true,
        orderEvidence: SingleStrategyOrderEvidence? = null,
    ) = LedgerDriftInput(
        code = "005930",
        authorityQuantity = authority,
        legs = legs,
        attributions = attributions,
        enabled = enabled,
        orderEvidence = orderEvidence,
    )

    // === incident 재현 — strategy_position qty=0 vs KIS 3, ledger 귀속 net=3 ===
    @Test
    fun `incident 재현 — qty0 leg + ledger net3 = authority3 → 3주 복원`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(strategyId = 8L, netFilledQuantity = 3)),
                ),
            )
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        assertEquals(0, adjust.strategySum)
        assertEquals(3, adjust.authorityQuantity)
        assertEquals(1, adjust.corrections.size)
        assertEquals(8L, adjust.corrections[0].strategyId)
        assertEquals(0, adjust.corrections[0].previousQuantity)
        assertEquals(3, adjust.corrections[0].newQuantity)
    }

    // === leg 자체 부재 — ledger 에만 net3 → 신규 leg 보정 ===
    @Test
    fun `leg 부재 + ledger net3 = authority3 → 신규 leg 3주`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = emptyList(),
                    attributions = listOf(LedgerAttribution(strategyId = 8L, netFilledQuantity = 3)),
                ),
            )
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        assertEquals(8L, adjust.corrections[0].strategyId)
        assertEquals(0, adjust.corrections[0].previousQuantity)
        assertEquals(3, adjust.corrections[0].newQuantity)
    }

    // === 다전략 부족 — ledger 로 정확 분배 ===
    @Test
    fun `다전략 — leg1=1·leg2=0, ledger net 2+3 = authority5 → 1→2·0→3`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 5,
                    legs = listOf(leg(1L, 1), leg(2L, 0)),
                    attributions =
                        listOf(
                            LedgerAttribution(1L, 2),
                            LedgerAttribution(2L, 3),
                        ),
                ),
            )
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        val byId = adjust.corrections.associateBy { it.strategyId }
        assertEquals(2, byId[1L]!!.newQuantity)
        assertEquals(3, byId[2L]!!.newQuantity)
    }

    // === 모호 — ledger 합이 authority 와 불일치 → WARN-only ===
    @Test
    fun `모호 — ledger net2 인데 authority3(외부 1주 혼재) → WarnUnderflow`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(strategyId = 8L, netFilledQuantity = 2)),
                ),
            )
        val warn = assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
        assertEquals(0, warn.strategySum)
        assertEquals(3, warn.authorityQuantity)
    }

    // === 모호 — ledger 귀속 0(전부 외부 주문) → WARN-only ===
    @Test
    fun `모호 — 귀속 ledger 비어있음(외부 매수) → WarnUnderflow`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = emptyList(),
                    attributions = emptyList(),
                ),
            )
        assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
    }

    // === 멱등 — 이미 정합(leg=3, ledger=3, authority=3) → NoChange(부족 아님) ===
    @Test
    fun `멱등 — 이미 정합이면 부족 아님 NoChange`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 3)),
                    attributions = listOf(LedgerAttribution(8L, 3)),
                ),
            )
        assertEquals(DriftCorrectionResult.NoChange, result)
    }

    // === 멱등 재실행 — 보정 후 같은 입력 두 번째 → corrections 없음(over-count 0) ===
    @Test
    fun `멱등 재실행 — 이미 ledger 목표 도달한 leg 는 corrections 제외`() {
        // leg1 이미 ledger 목표(2) 도달, leg2 만 미달(0→3). 합 2+3=5=authority.
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 5,
                    legs = listOf(leg(1L, 2), leg(2L, 0)),
                    attributions = listOf(LedgerAttribution(1L, 2), LedgerAttribution(2L, 3)),
                ),
            )
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        // leg1 은 이미 목표라 제외, leg2 만 보정.
        assertEquals(1, adjust.corrections.size)
        assertEquals(2L, adjust.corrections[0].strategyId)
    }

    // === 초과 방향은 본 함수 책임 아님 → NoChange(PositionDriftCorrection 이 처리) ===
    @Test
    fun `초과 방향 — 본 함수 미개입 NoChange`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 7)),
                    attributions = listOf(LedgerAttribution(8L, 3)),
                ),
            )
        assertEquals(DriftCorrectionResult.NoChange, result)
    }

    // === 토글 off → Disabled ===
    @Test
    fun `토글 off → Disabled`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(8L, 3)),
                    enabled = false,
                ),
            )
        assertEquals(DriftCorrectionResult.Disabled, result)
    }

    // === SELL 과다로 ledger net 음수인 전략은 0 절상 — 정합 검산에 0 으로 반영 ===
    @Test
    fun `ledger net 음수(SELL 과다) → 0 절상, 정합 안 맞으면 WARN`() {
        // leg1=0, ledger1 net=-1(0 절상) + leg2 ledger=3. correctedSum=0+3=3 != authority 4 → WARN.
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 4,
                    legs = listOf(leg(1L, 0), leg(2L, 0)),
                    attributions = listOf(LedgerAttribution(1L, -1), LedgerAttribution(2L, 3)),
                ),
            )
        assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
    }

    // === (c) 단일 전략 부족 자동보정 — ledger net 부족하나 주문 evidence 정합 → Adjust 완화 (2026-06-10 amend) ===
    @Test
    fun `완화 — 단일 전략 leg0, ledger net 부족(부분체결 누락), 주문 evidence net3 = authority3 → Adjust`() {
        // incident: 부분체결 일부 execution 이 ledger net 집계에서까지 누락돼 net=2(부족)로 1차 검산 실패.
        // 그러나 그 전략의 FILLED 주문 합계 evidence=3 이 authority=3 과 정합 → 단일 전략이라 모호성 0 → 보정.
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(strategyId = 8L, netFilledQuantity = 2)),
                    orderEvidence = SingleStrategyOrderEvidence(strategyId = 8L, netFilledFromOrders = 3),
                ),
            )
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        assertEquals(1, adjust.corrections.size)
        assertEquals(8L, adjust.corrections[0].strategyId)
        assertEquals(0, adjust.corrections[0].previousQuantity)
        assertEquals(3, adjust.corrections[0].newQuantity)
    }

    // === (c') 완화 — leg 자체 부재 + evidence 정합 → 신규 leg authority ===
    @Test
    fun `완화 — leg 부재 + 주문 evidence net5 = authority5 → 신규 leg 5주`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 5,
                    legs = emptyList(),
                    attributions = emptyList(),
                    orderEvidence = SingleStrategyOrderEvidence(strategyId = 8L, netFilledFromOrders = 5),
                ),
            )
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        assertEquals(8L, adjust.corrections[0].strategyId)
        assertEquals(0, adjust.corrections[0].previousQuantity)
        assertEquals(5, adjust.corrections[0].newQuantity)
    }

    // === (d) 다전략 모호 — evidence 있어도 보유 기여 전략 2개면 완화 거부 → WarnUnderflow 유지 ===
    @Test
    fun `완화 거부 — 다전략(leg1 비-zero + 전략2 ledger net) 혼재 → WarnUnderflow`() {
        // leg1=1(비-zero) + 전략2 ledger net=1. 보유 기여 전략 2개 → evidence(전략2) 가 단일이라 주장해도 모호 → 거부.
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 5,
                    legs = listOf(leg(1L, 1), leg(2L, 0)),
                    attributions = listOf(LedgerAttribution(2L, 1)),
                    orderEvidence = SingleStrategyOrderEvidence(strategyId = 2L, netFilledFromOrders = 5),
                ),
            )
        assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
    }

    // === (d') 완화 거부 — evidence net 이 authority 와 불일치(외부 혼재) → WarnUnderflow ===
    @Test
    fun `완화 거부 — 단일 전략이나 evidence net2 != authority3(외부 1주) → WarnUnderflow`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(8L, 2)),
                    orderEvidence = SingleStrategyOrderEvidence(strategyId = 8L, netFilledFromOrders = 2),
                ),
            )
        assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
    }

    // === 완화 미주입(orderEvidence=null) — 현행 WarnUnderflow 회귀 안전 ===
    @Test
    fun `완화 미주입 — evidence null 이면 현행 WarnUnderflow(회귀 안전)`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(8L, 2)),
                    orderEvidence = null,
                ),
            )
        assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
    }

    // === 완화 토글 off — enabled=false 면 evidence 있어도 Disabled ===
    @Test
    fun `완화 토글 off — enabled false 면 evidence 무관 Disabled`() {
        val result =
            LedgerAttributedDriftCorrection.evaluate(
                input(
                    authority = 3,
                    legs = listOf(leg(8L, 0)),
                    attributions = listOf(LedgerAttribution(8L, 2)),
                    enabled = false,
                    orderEvidence = SingleStrategyOrderEvidence(strategyId = 8L, netFilledFromOrders = 3),
                ),
            )
        assertEquals(DriftCorrectionResult.Disabled, result)
    }
}
