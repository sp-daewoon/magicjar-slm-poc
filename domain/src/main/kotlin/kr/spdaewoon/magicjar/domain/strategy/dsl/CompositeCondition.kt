package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL CompositeCondition.
 *
 * Source: parser.py:L283 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 *
 * `close > ma(60) AND change > 0` → CompositeCondition(Condition(close,GT,...), AND, Condition(change,GT,0)).
 *
 * 우선순위: OR < AND (parser.py:L412-L430 정합).
 */
data class CompositeCondition(
    val left: DslNode,
    val operator: LogicalOperator,
    val right: DslNode,
) : DslNode() {
    /**
     * 좌·우 자식 노드의 requiredDays max.
     */
    fun requiredDays(): Int {
        val l =
            when (left) {
                is Condition -> left.requiredDays()
                is CompositeCondition -> left.requiredDays()
                else -> 10
            }
        val r =
            when (right) {
                is Condition -> right.requiredDays()
                is CompositeCondition -> right.requiredDays()
                else -> 10
            }
        return maxOf(l, r)
    }
}
