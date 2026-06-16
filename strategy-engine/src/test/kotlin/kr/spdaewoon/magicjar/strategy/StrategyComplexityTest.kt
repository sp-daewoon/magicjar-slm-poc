package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Static 검증 — depth · 노드 수 · indicator 호출 한도 · 화이트리스트 (ADR 0047 §2-1).
 *
 * spec §1-2 DoD #4·#5·#6·#7 정합.
 */
class StrategyComplexityTest {
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )

    @Test
    fun `simple threshold — LOW`() {
        val ast = parser.parse("rsi(14) < 30")
        val complexity = parser.validateStatic(ast)
        assertEquals(ComplexityClass.LOW, complexity.complexityClass)
        assertTrue(complexity.indicatorCallCount >= 1)
        assertTrue(complexity.nodeCount >= 3)
    }

    @Test
    fun `crossover with one indicator each — LOW`() {
        val ast = parser.parse("ma(5) crosses_above ma(20)")
        val complexity = parser.validateStatic(ast)
        assertEquals(ComplexityClass.LOW, complexity.complexityClass)
        assertEquals(2, complexity.indicatorCallCount)
    }

    @Test
    fun `composite with arithmetic — multiple indicator calls`() {
        val ast = parser.parse("ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5")
        val complexity = parser.validateStatic(ast)
        assertEquals(4, complexity.indicatorCallCount) // ma(5), ma(20), volume, volume_ma(20)
    }

    @Test
    fun `astDepth limit too small triggers DslDepthExceededException`() {
        val tinyParser =
            KisYamlDslParser(
                astDepthLimit = 2, // 매우 작게
                nodeCountLimit = 10000,
                indicatorCallLimit = 50,
            )
        val ast = tinyParser.parse("ma(5) crosses_above ma(20) AND rsi(14) < 30")
        assertThrows(StrategyException.DslDepthExceededException::class.java) {
            tinyParser.validateStatic(ast)
        }
    }

    @Test
    fun `nodeCount limit triggers DslNodeCountExceededException`() {
        val tinyParser =
            KisYamlDslParser(
                astDepthLimit = 32,
                nodeCountLimit = 2, // ma + ma + Condition = 3 노드 — limit=2 초과
                indicatorCallLimit = 50,
            )
        val ast = tinyParser.parse("ma(5) crosses_above ma(20)")
        assertThrows(StrategyException.DslNodeCountExceededException::class.java) {
            tinyParser.validateStatic(ast)
        }
    }

    @Test
    fun `indicatorCallCount limit triggers DslIndicatorCallExceededException`() {
        val tinyParser =
            KisYamlDslParser(
                astDepthLimit = 32,
                nodeCountLimit = 10000,
                indicatorCallLimit = 1, // 매우 작게
            )
        val ast = tinyParser.parse("ma(5) crosses_above ma(20)") // 2 indicators
        assertThrows(StrategyException.DslIndicatorCallExceededException::class.java) {
            tinyParser.validateStatic(ast)
        }
    }

    @Test
    fun `unknown identifier triggers UnknownDslSymbolException`() {
        val ast = parser.parse("evil_func() > 0")
        assertThrows(StrategyException.UnknownDslSymbolException::class.java) {
            parser.validateStatic(ast)
        }
    }

    @Test
    fun `consecutive identifier passes whitelist (T-PAT graceful)`() {
        val ast = parser.parse("consecutive(up) >= 5")
        // 화이트리스트 통과 — 등록 자체는 OK (T-PAT 미진입 graceful, spec §8-4)
        val complexity = parser.validateStatic(ast)
        assertEquals(1, complexity.indicatorCallCount)
    }
}
