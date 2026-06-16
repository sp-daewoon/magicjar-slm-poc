package kr.spdaewoon.magicjar.strategy.integrity

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PositionDriftCorrection] 단위 테스트 (순수 함수 — 결정론·100% 커버 · ADR 0115 §P1-1).
 *
 * 규칙 LOCK: 초과만 내림 / 부족 WARN(보정 X) / 하한 0 / 다전략 차감순서(updatedAt 최신 우선) /
 * realized_pnl 불변(수량만 반환) / 일치 NoChange / 토글 off Disabled.
 */
class PositionDriftCorrectionTest {
    private val t0 = Instant.parse("2026-06-08T01:00:00Z")
    private val t1 = Instant.parse("2026-06-08T02:00:00Z")
    private val t2 = Instant.parse("2026-06-08T03:00:00Z")

    private fun leg(
        strategyId: Long,
        quantity: Int,
        updatedAt: Instant,
    ) = StrategyPositionLeg(strategyId = strategyId, quantity = quantity, updatedAt = updatedAt)

    private fun input(
        authority: Int,
        legs: List<StrategyPositionLeg>,
        enabled: Boolean = true,
    ) = DriftCorrectionInput(code = "005930", authorityQuantity = authority, legs = legs, enabled = enabled)

    // === 초과 — 단일 전략(incident #8 — strategySum 7 > authority 5) ===
    @Test
    fun `초과 단일전략 7대5 — 2주 내림 보정`() {
        val result = PositionDriftCorrection.evaluate(input(authority = 5, legs = listOf(leg(8L, 7, t0))))
        val adjust = assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        assertEquals(7, adjust.strategySum)
        assertEquals(5, adjust.authorityQuantity)
        assertEquals(1, adjust.corrections.size)
        assertEquals(8L, adjust.corrections[0].strategyId)
        assertEquals(7, adjust.corrections[0].previousQuantity)
        assertEquals(5, adjust.corrections[0].newQuantity)
        // 보정 후 합 == 권위 불변식
        assertEquals(5, adjust.corrections.sumOf { it.newQuantity })
    }

    // === 다전략 차감 순서 — updatedAt 최신 leg부터 ===
    @Test
    fun `다전략 초과 — updatedAt 최신 leg부터 차감 (결정론)`() {
        // sum=10, authority=6 → excess=4. 최신(t2, sid=2) 5주에서 4 차감 → 1. 나머지 그대로.
        val legs = listOf(leg(1L, 5, t0), leg(2L, 5, t2))
        val adjust =
            assertInstanceOf(
                DriftCorrectionResult.Adjust::class.java,
                PositionDriftCorrection.evaluate(input(authority = 6, legs = legs)),
            )
        assertEquals(1, adjust.corrections.size) // 변경된 leg만 포함
        assertEquals(2L, adjust.corrections[0].strategyId) // 최신부터
        assertEquals(5, adjust.corrections[0].previousQuantity)
        assertEquals(1, adjust.corrections[0].newQuantity)
        // 변경 안 된 leg(sid=1, 5주) + 변경 leg 합 == authority
        assertEquals(6, 5 + adjust.corrections[0].newQuantity)
    }

    @Test
    fun `다전략 초과 — 최신 leg 소진 후 다음 leg로 cascade`() {
        // sum=10, authority=3 → excess=7. 최신(t2,sid=2) 4 전부 차감(0) → 남은 3, 다음(t1,sid=3) 3 차감 → 0, 남은 0.
        // 가장 오래된(t0,sid=1) 2주는 그대로. 합 2 == authority? 2 != 3 → 더 차감 필요.
        // 재계산: sum = 2+4+3 = 9, authority=3, excess=6. 최신 t2(4)→0(차감4,남2), t1(3)→1(차감2,남0). t0(2) 유지.
        val legs = listOf(leg(1L, 2, t0), leg(2L, 4, t2), leg(3L, 3, t1))
        val adjust =
            assertInstanceOf(
                DriftCorrectionResult.Adjust::class.java,
                PositionDriftCorrection.evaluate(input(authority = 3, legs = legs)),
            )
        // 차감 순서: t2(sid2) → t1(sid3). t0(sid1)은 미변경.
        assertEquals(listOf(2L, 3L), adjust.corrections.map { it.strategyId })
        assertEquals(0, adjust.corrections[0].newQuantity) // sid2 소진
        assertEquals(1, adjust.corrections[1].newQuantity) // sid3 부분
        // 미변경 t0(2) + 변경 후 합(0+1) == authority 3
        assertEquals(3, 2 + adjust.corrections.sumOf { it.newQuantity })
    }

    @Test
    fun `동일 updatedAt — strategyId 큰 쪽부터 차감 (tie-break 결정론)`() {
        // 동일 t0, sum=8, authority=5 → excess=3. sid 큰(2L) 4주 우선 → 1. sid 1L 4주 유지.
        val legs = listOf(leg(1L, 4, t0), leg(2L, 4, t0))
        val adjust =
            assertInstanceOf(
                DriftCorrectionResult.Adjust::class.java,
                PositionDriftCorrection.evaluate(input(authority = 5, legs = legs)),
            )
        assertEquals(2L, adjust.corrections[0].strategyId)
        assertEquals(1, adjust.corrections[0].newQuantity)
    }

    // === 하한 0 ===
    @Test
    fun `하한 0 — authority 0이면 전 leg 0으로 (음수 미발생)`() {
        val legs = listOf(leg(1L, 3, t0), leg(2L, 4, t2))
        val adjust =
            assertInstanceOf(
                DriftCorrectionResult.Adjust::class.java,
                PositionDriftCorrection.evaluate(input(authority = 0, legs = legs)),
            )
        assertTrue(adjust.corrections.all { it.newQuantity == 0 })
        assertTrue(adjust.corrections.all { it.newQuantity >= 0 })
        assertEquals(0, adjust.corrections.sumOf { it.newQuantity })
        // 전량 청산 신호
        assertTrue(adjust.corrections.all { it.willBeClosed })
    }

    @Test
    fun `음수 authority는 0으로 절상`() {
        val adjust =
            assertInstanceOf(
                DriftCorrectionResult.Adjust::class.java,
                PositionDriftCorrection.evaluate(input(authority = -5, legs = listOf(leg(1L, 3, t0)))),
            )
        assertEquals(0, adjust.authorityQuantity)
        assertEquals(0, adjust.corrections[0].newQuantity)
    }

    // === 부족 — WARN(보정 X) ===
    @Test
    fun `부족 strategySum 3 lt authority 5 — WarnUnderflow 보정 안 함`() {
        val result = PositionDriftCorrection.evaluate(input(authority = 5, legs = listOf(leg(1L, 3, t0))))
        val warn = assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
        assertEquals(3, warn.strategySum)
        assertEquals(5, warn.authorityQuantity)
        // 보정 leg 없음 — 없는 물량 인식 회피
    }

    @Test
    fun `빈 leg 목록 + authority 양수 — 부족으로 WarnUnderflow`() {
        val result = PositionDriftCorrection.evaluate(input(authority = 5, legs = emptyList()))
        assertInstanceOf(DriftCorrectionResult.WarnUnderflow::class.java, result)
    }

    // === 일치 — NoChange ===
    @Test
    fun `일치 strategySum == authority — NoChange`() {
        val result =
            PositionDriftCorrection.evaluate(input(authority = 7, legs = listOf(leg(1L, 4, t0), leg(2L, 3, t2))))
        assertEquals(DriftCorrectionResult.NoChange, result)
    }

    @Test
    fun `빈 leg + authority 0 — NoChange`() {
        assertEquals(
            DriftCorrectionResult.NoChange,
            PositionDriftCorrection.evaluate(input(authority = 0, legs = emptyList())),
        )
    }

    // === 토글 off — Disabled ===
    @Test
    fun `enabled false — 초과여도 Disabled (R2 WARN-only fallback)`() {
        val result =
            PositionDriftCorrection.evaluate(input(authority = 5, legs = listOf(leg(1L, 7, t0)), enabled = false))
        assertEquals(DriftCorrectionResult.Disabled, result)
    }

    @Test
    fun `default enabled true (ENV default fallback)`() {
        // 입력에서 enabled 생략 → DriftCorrectionDefaults.AUTO_CORRECT_ENABLED(true)
        val result =
            PositionDriftCorrection.evaluate(
                DriftCorrectionInput(code = "005930", authorityQuantity = 5, legs = listOf(leg(1L, 7, t0))),
            )
        assertInstanceOf(DriftCorrectionResult.Adjust::class.java, result)
        assertTrue(DriftCorrectionDefaults.AUTO_CORRECT_ENABLED)
    }

    // === realized_pnl 불변 — 함수는 수량만 반환 (필드 부재로 구조적 보장) ===
    @Test
    fun `LegCorrection은 수량만 — realized_pnl avgPrice 필드 미보유 (불변 구조 보장)`() {
        val adjust =
            assertInstanceOf(
                DriftCorrectionResult.Adjust::class.java,
                PositionDriftCorrection.evaluate(input(authority = 5, legs = listOf(leg(8L, 7, t0)))),
            )
        val correction = adjust.corrections[0]
        // 반환 타입이 수량(previous/new)만 — realized_pnl 산정/노출 경로 자체가 없음.
        val fieldNames =
            LegCorrection::class.java.declaredFields
                .map { it.name }
                .toSet()
        assertTrue("strategyId" in fieldNames)
        assertTrue("previousQuantity" in fieldNames)
        assertTrue("newQuantity" in fieldNames)
        assertTrue(fieldNames.none { it.contains("realized", ignoreCase = true) })
        assertTrue(fieldNames.none { it.contains("avgPrice", ignoreCase = true) })
        assertTrue(fieldNames.none { it.contains("pnl", ignoreCase = true) })
        assertEquals(8L, correction.strategyId)
    }

    @Test
    fun `결정론 — 동일 입력 반복 호출 동일 출력`() {
        val legs = listOf(leg(1L, 5, t0), leg(2L, 5, t2), leg(3L, 3, t1))
        val first = PositionDriftCorrection.evaluate(input(authority = 4, legs = legs))
        val second = PositionDriftCorrection.evaluate(input(authority = 4, legs = legs.reversed()))
        // 입력 순서가 달라도(legs vs reversed) 정렬 결정론으로 동일 결과
        assertEquals(first, second)
    }
}
