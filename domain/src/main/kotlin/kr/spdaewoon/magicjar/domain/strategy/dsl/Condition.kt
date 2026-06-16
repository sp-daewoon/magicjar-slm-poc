package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL Condition.
 *
 * Source: parser.py:L251 + L283-L296 (`get_required_days()`) · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2 + §2-6
 *
 * `rsi(14) < 30` → Condition(IndicatorRef("rsi",[14]), LT, LiteralValue(30), THRESHOLD).
 *
 * conditionType 결정 룰 (parser.py:L361-L390):
 * - operator == CROSSES_ABOVE | CROSSES_BELOW → CROSSOVER
 * - operator == BREAKS → BREAKOUT
 * - left = `consecutive(...)` IndicatorRef → PATTERN
 * - 그 외 비교 operator → THRESHOLD
 */
data class Condition(
    val left: DslNode,
    val operator: Operator,
    val right: DslNode,
    val conditionType: ConditionType = ConditionType.THRESHOLD,
) : DslNode() {
    /**
     * KIS `Condition.get_required_days()` port (parser.py:L283-L296).
     *
     * 산식: max(10, requiredDays(left) + 10, requiredDays(right) + 10).
     *  - Condition은 lookback 비교용으로 +10 시리즈 안전 buffer.
     *  - ArithmeticExpr는 좌·우 max.
     *  - LiteralValue·기타는 0.
     */
    fun requiredDays(): Int = maxOf(10, extract(left) + 10, extract(right) + 10)

    private fun extract(node: DslNode): Int =
        when (node) {
            is IndicatorRef -> node.requiredDays()
            is ArithmeticExpr -> maxOf(extract(node.left), extract(node.right))
            else -> 0
        }
}
