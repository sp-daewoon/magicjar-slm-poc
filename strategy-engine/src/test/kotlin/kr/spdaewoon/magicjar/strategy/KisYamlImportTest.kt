package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * KIS .kis.yaml import — KisYamlDslParser.parseYaml() 검증.
 *
 * spec §1-2 DoD #11·#18 정합 + ADR 0022 §2-3.
 */
class KisYamlImportTest {
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )

    @Test
    fun `KIS golden cross yaml imports correctly`() {
        val yaml =
            """
            name: golden_cross_5_20
            name_ko: "골든크로스 5/20"
            universe: KOSPI200
            buy_condition: "ma(5) crosses_above ma(20)"
            sell_condition: "ma(5) crosses_below ma(20)"
            params:
              initial_capital: 10000000
              position_size: 0.1
            """.trimIndent()

        val def = parser.parseYaml(yaml)

        assertEquals("golden_cross_5_20", def.name)
        assertEquals("골든크로스 5/20", def.nameKo)
        assertNotNull(def.buyCondition)
        assertNotNull(def.sellCondition)

        val buy = assertInstanceOf(Condition::class.java, def.buyCondition)
        assertEquals(ConditionType.CROSSOVER, buy.conditionType)
        assertEquals(Operator.CROSSES_ABOVE, buy.operator)

        val sell = assertInstanceOf(Condition::class.java, def.sellCondition)
        assertEquals(ConditionType.CROSSOVER, sell.conditionType)
        assertEquals(Operator.CROSSES_BELOW, sell.operator)

        // params 보존
        assertEquals(10000000, def.params["initial_capital"])
        assertEquals(0.1, def.params["position_size"])
    }

    @Test
    fun `RSI mean reversion yaml`() {
        val yaml =
            """
            name: rsi_mean_reversion
            name_ko: "RSI 과매도 매수"
            buy_condition: "rsi(14) < 30"
            sell_condition: "rsi(14) > 70"
            """.trimIndent()

        val def = parser.parseYaml(yaml)
        assertEquals("rsi_mean_reversion", def.name)
        val buy = assertInstanceOf(Condition::class.java, def.buyCondition)
        assertEquals(ConditionType.THRESHOLD, buy.conditionType)
        val rsi = assertInstanceOf(IndicatorRef::class.java, buy.left)
        assertEquals("rsi", rsi.name)
        assertEquals(14, rsi.params[0])
        val rhs = assertInstanceOf(LiteralValue::class.java, buy.right)
        assertEquals(java.math.BigDecimal("30"), rhs.value)
    }

    @Test
    fun `yaml without sell_condition — sellCondition is null`() {
        val yaml =
            """
            name: only_buy
            buy_condition: "rsi(14) < 30"
            """.trimIndent()

        val def = parser.parseYaml(yaml)
        assertNotNull(def.buyCondition)
        assertNull(def.sellCondition)
    }

    @Test
    fun `yaml missing name — throws DslSyntaxException`() {
        val yaml =
            """
            buy_condition: "rsi(14) < 30"
            """.trimIndent()

        assertThrows(StrategyException.DslSyntaxException::class.java) {
            parser.parseYaml(yaml)
        }
    }

    @Test
    fun `yaml with malformed buy_condition — DslSyntaxException`() {
        val yaml =
            """
            name: bad_strategy
            buy_condition: "ma(5) crosses_above"
            """.trimIndent()

        assertThrows(StrategyException.DslSyntaxException::class.java) {
            parser.parseYaml(yaml)
        }
    }
}
