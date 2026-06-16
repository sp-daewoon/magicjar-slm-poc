package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticExpr
import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticOperator
import kr.spdaewoon.magicjar.domain.strategy.dsl.CompositeCondition
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.LogicalOperator
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

/**
 * KIS Strategy Builder DSL parser fixture 테스트.
 *
 * Source: parser.py:L1-L16 docstring 8 fixture (KIS raw 정합) — pr-35-kis-dsl-raw-verification.md §4 LOCK.
 *
 * spec §1-2 DoD #2 + §6-3 LOCK 정합 — 자체 추론 0.
 */
class KisDslParserFixtureTest {
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )

    @ParameterizedTest(name = "[{index}] `{0}` -> {1}")
    @CsvSource(
        // (입력, 예상 conditionType)
        "ma(5) crosses_above ma(20),CROSSOVER",
        "ema(12) crosses_below ema(26),CROSSOVER",
        "rsi(14) < 30,THRESHOLD",
        "rsi(14) > 70,THRESHOLD",
        "close breaks high(250),BREAKOUT",
        "consecutive(up) >= 5,PATTERN",
        "volume > volume_ma(20) * 1.5,THRESHOLD",
        "returns(1) > -3.5,THRESHOLD",
    )
    fun `KIS raw 8 fixture parses to expected ConditionType`(
        input: String,
        expected: String,
    ) {
        val ast = parser.parse(input)
        val cond = assertInstanceOf(Condition::class.java, ast)
        assertEquals(ConditionType.valueOf(expected), cond.conditionType)
    }

    @Test
    fun `composite AND parses to CompositeCondition`() {
        val ast = parser.parse("close > ma(60) AND change > 0")
        val composite = assertInstanceOf(CompositeCondition::class.java, ast)
        assertEquals(LogicalOperator.AND, composite.operator)
        assertInstanceOf(Condition::class.java, composite.left)
        assertInstanceOf(Condition::class.java, composite.right)
    }

    @Test
    fun `composite OR with negative literal`() {
        val ast = parser.parse("rsi(14) < 30 OR cci(20) < -100")
        val composite = assertInstanceOf(CompositeCondition::class.java, ast)
        assertEquals(LogicalOperator.OR, composite.operator)

        val right = assertInstanceOf(Condition::class.java, composite.right)
        // -100 — LiteralValue 단항 마이너스 정합
        val rhs = right.right
        if (rhs is LiteralValue) {
            assertEquals(BigDecimal("-100"), rhs.value)
        } else {
            // ArithmeticExpr(0 - 100) 가능 — 둘 다 정합
            assertInstanceOf(ArithmeticExpr::class.java, rhs)
        }
    }

    @Test
    fun `parenthesized composite — operator precedence`() {
        val ast = parser.parse("(close > ma(60) AND change > 0) OR rsi(14) < 30")
        // 최상위는 OR
        val outer = assertInstanceOf(CompositeCondition::class.java, ast)
        assertEquals(LogicalOperator.OR, outer.operator)
        // 좌측은 AND composite
        val left = assertInstanceOf(CompositeCondition::class.java, outer.left)
        assertEquals(LogicalOperator.AND, left.operator)
        // 우측은 단순 condition
        assertInstanceOf(Condition::class.java, outer.right)
    }

    @Test
    fun `arithmetic — standard precedence MUL_DIV binds tighter than ADD_SUB`() {
        // `close < ma(20) - atr(14) * 2` →
        //   THRESHOLD lhs=close · rhs=ArithmeticExpr(ma, SUB, ArithmeticExpr(atr, MUL, 2))
        val ast = parser.parse("close < ma(20) - atr(14) * 2")
        val cond = assertInstanceOf(Condition::class.java, ast)
        assertEquals(Operator.LT, cond.operator)
        val rhs = assertInstanceOf(ArithmeticExpr::class.java, cond.right)
        assertEquals(ArithmeticOperator.SUB, rhs.operator)
        val rhsRight = assertInstanceOf(ArithmeticExpr::class.java, rhs.right)
        assertEquals(ArithmeticOperator.MUL, rhsRight.operator)
    }

    @Test
    fun `arithmetic — one plus two times three parses as seven`() {
        val ast = parser.parse("1 + 2 * 3 == 7")
        val cond = assertInstanceOf(Condition::class.java, ast)
        val lhs = assertInstanceOf(ArithmeticExpr::class.java, cond.left)
        assertEquals(ArithmeticOperator.ADD, lhs.operator)
        val rhs = assertInstanceOf(ArithmeticExpr::class.java, lhs.right)
        assertEquals(ArithmeticOperator.MUL, rhs.operator)
    }

    @Test
    fun `percent literal — 5 percent normalizes to 0_05`() {
        val ast = parser.parse("returns(5) > 5%")
        val cond = assertInstanceOf(Condition::class.java, ast)
        val rhs = assertInstanceOf(LiteralValue::class.java, cond.right)
        assertTrue(rhs.isPercent, "5% should be marked isPercent=true")
        assertEquals(BigDecimal("0.05000000"), rhs.normalized())
    }

    @Test
    fun `unknown identifier triggers UnknownDslSymbolException via validateStatic`() {
        val ast = parser.parse("system_exit() > 0")
        val ex =
            assertThrows(StrategyException.UnknownDslSymbolException::class.java) {
                parser.validateStatic(ast)
            }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("system_exit"))
    }

    @Test
    fun `bare close identifier parses to IndicatorRef without params`() {
        val ast = parser.parse("close > 0")
        val cond = assertInstanceOf(Condition::class.java, ast)
        val left = assertInstanceOf(IndicatorRef::class.java, cond.left)
        assertEquals("close", left.name)
        assertEquals(0, left.params.size)
    }

    @Test
    fun `bare open identifier passes validateStatic — Strategy Builder OHLCV contract`() {
        val ast = parser.parse("open > 0")
        parser.validateStatic(ast)
        val cond = assertInstanceOf(Condition::class.java, ast)
        val left = assertInstanceOf(IndicatorRef::class.java, cond.left)
        assertEquals("open", left.name)
        assertEquals(0, left.params.size)
    }

    @Test
    fun `order_block accepts identifier args engulfing body — round-trip to IndicatorRef params`() {
        // NUMBER | IDENTIFIER 인자 혼합 — `up`·`down` 선례 정합 (parseArg L424)
        val ast = parser.parse("ob_bullish_active(20, 20, 100, engulfing, body) > 0")
        parser.validateStatic(ast)
        val cond = assertInstanceOf(Condition::class.java, ast)
        val left = assertInstanceOf(IndicatorRef::class.java, cond.left)
        assertEquals("ob_bullish_active", left.name)
        assertEquals(5, left.params.size)
        assertEquals(20, left.params[0])
        assertEquals(20, left.params[1])
        assertEquals(100, left.params[2])
        assertEquals("engulfing", left.params[3])
        assertEquals("body", left.params[4])

        // mapper 매핑까지 round-trip — detection/range 반영
        val spec = DslIdentifierMapper().toIndicatorSpec(left)
        assertEquals("order_block", spec.name)
        assertEquals("engulfing", spec.params["detection"])
        assertEquals("body", spec.params["range"])
    }
}
