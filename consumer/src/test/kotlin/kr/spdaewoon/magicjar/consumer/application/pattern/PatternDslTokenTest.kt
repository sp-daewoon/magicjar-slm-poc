package kr.spdaewoon.magicjar.consumer.application.pattern

import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * DSL `pattern_detected("name")` 토큰 통합 검증 — KisYamlDslParser 화이트리스트 + AST.
 *
 * Source: 사용자 메시지 #3 (DSL pattern_detected 토큰 추가 의무) + spec §6-2.
 *
 * 검증 항목:
 * 1. `pattern_detected("hammer") == 1` 형태 파싱 OK
 * 2. parseTree에 IndicatorRef(name="pattern_detected", params=["hammer"]) 정합
 * 3. ConditionType=PATTERN 분류 정합 (KisYamlDslParser.isPatternIndicator)
 * 4. validateStatic 화이트리스트 통과 (UnknownDslSymbolException 미발생)
 * 5. 기존 `pattern("hammer")` 토큰도 그대로 파싱 OK (regression 방지)
 */
class PatternDslTokenTest {
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )

    @Test
    fun `parse — pattern_detected token AST shape`() {
        // pattern_detected는 boolean 의미지만 KIS DSL은 numeric 비교만 — 1 매칭 시 동치
        val ast = parser.parse("pattern_detected(hammer) == 1")
        assertNotNull(ast)
        ast as Condition
        assertEquals(ConditionType.PATTERN, ast.conditionType)
        val left = ast.left as IndicatorRef
        assertEquals("pattern_detected", left.name)
        assertEquals(listOf<Any>("hammer"), left.params)
        val right = ast.right as LiteralValue
        assertEquals(java.math.BigDecimal(1), right.value)
    }

    @Test
    fun `parse — pattern token still works (regression)`() {
        val ast = parser.parse("pattern(engulfing) == 1")
        assertNotNull(ast)
        ast as Condition
        assertEquals(ConditionType.PATTERN, ast.conditionType)
        val left = ast.left as IndicatorRef
        assertEquals("pattern", left.name)
    }

    @Test
    fun `validateStatic — pattern_detected passes whitelist`() {
        val ast = parser.parse("pattern_detected(doji) == 1")
        // No throw → whitelist contains pattern_detected
        val complexity = parser.validateStatic(ast)
        assertNotNull(complexity)
    }

    @Test
    fun `validateStatic — pattern_detected combined with consecutive`() {
        val ast = parser.parse("pattern_detected(hammer) == 1 AND consecutive(up) >= 3")
        val complexity = parser.validateStatic(ast)
        assertNotNull(complexity)
    }
}
