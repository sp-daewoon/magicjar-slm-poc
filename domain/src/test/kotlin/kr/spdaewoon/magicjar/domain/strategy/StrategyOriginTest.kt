package kr.spdaewoon.magicjar.domain.strategy

import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [StrategyOrigin] · [Strategy.origin] 단위 테스트 — ADR 0122 R1 (도메인 add-only 계약 고정).
 *
 * 핵심 계약 (spec §3-R1):
 *  1. enum 멤버 = USER_DSL · PAPER_DISCOVERY (2개, 발명 0).
 *  2. `Strategy.origin` default = USER_DSL → 기존 생성 지점 무수정 하위호환(zero-regression).
 *  3. 페이퍼 식별 조합 = origin=PAPER_DISCOVERY (capital 분기·MOCK 강제 경로 1급 키).
 *     페이퍼 주문은 OrderTrigger.STRATEGY_SIGNAL 재사용 + mode=MOCK + origin 조합(§3-R1 (a) LOCK) —
 *     신규 OrderTrigger 값을 발명하지 않으므로 origin이 유일한 페이퍼 구분 차원임을 고정한다.
 */
class StrategyOriginTest {
    private fun complexity() =
        StrategyComplexity(
            astDepth = 1,
            nodeCount = 1,
            indicatorCallCount = 1,
            estimatedEvalTimeMs = 1,
            complexityClass = ComplexityClass.LOW,
        )

    private fun strategy(origin: StrategyOrigin? = null) =
        if (origin == null) {
            // origin 인자를 아예 주지 않는 legacy 호출 (default 적용 검증)
            Strategy(
                id = 1L,
                userId = 100L,
                definition =
                    StrategyDefinition(
                        dsl = DslStrategyDefinition(name = "t", buyCondition = null),
                        tf = "1d",
                    ),
                complexity = complexity(),
            )
        } else {
            Strategy(
                id = 1L,
                userId = 100L,
                definition =
                    StrategyDefinition(
                        dsl = DslStrategyDefinition(name = "t", buyCondition = null),
                        tf = "1d",
                    ),
                complexity = complexity(),
                origin = origin,
            )
        }

    // --- enum 계약 ---

    @Test
    fun `enum has exactly USER_DSL and PAPER_DISCOVERY`() {
        assertEquals(
            listOf("USER_DSL", "PAPER_DISCOVERY"),
            StrategyOrigin.entries.map { it.name },
        )
    }

    @Test
    fun `isPaper true only for PAPER_DISCOVERY`() {
        assertFalse(StrategyOrigin.USER_DSL.isPaper())
        assertTrue(StrategyOrigin.PAPER_DISCOVERY.isPaper())
    }

    // --- Strategy.origin default (하위호환 LOCK) ---

    @Test
    fun `legacy Strategy construction without origin defaults to USER_DSL`() {
        val s = strategy(origin = null)
        assertEquals(StrategyOrigin.USER_DSL, s.origin)
        assertFalse(s.isPaperDiscovery())
    }

    @Test
    fun `paper discovery strategy is identified by origin`() {
        val s = strategy(origin = StrategyOrigin.PAPER_DISCOVERY)
        assertEquals(StrategyOrigin.PAPER_DISCOVERY, s.origin)
        assertTrue(s.isPaperDiscovery())
    }

    @Test
    fun `user dsl strategy is not paper discovery`() {
        val s = strategy(origin = StrategyOrigin.USER_DSL)
        assertFalse(s.isPaperDiscovery())
    }

    // --- add-only 무영향 검증 (origin 변경이 다른 메타에 누수 0) ---

    @Test
    fun `origin does not affect enabled or fixedStopLoss semantics`() {
        val paper =
            Strategy(
                id = 2L,
                userId = 100L,
                definition =
                    StrategyDefinition(
                        dsl = DslStrategyDefinition(name = "t", buyCondition = null),
                        tf = "1d",
                        fixedStopLoss = BigDecimal("5.00"),
                    ),
                complexity = complexity(),
                origin = StrategyOrigin.PAPER_DISCOVERY,
            )
        // origin은 enabled/disabledReason axis와 직교 — 페이퍼라도 활성 판정은 동일 규칙
        assertTrue(paper.isEnabled())
        assertTrue(paper.isPaperDiscovery())
    }
}
