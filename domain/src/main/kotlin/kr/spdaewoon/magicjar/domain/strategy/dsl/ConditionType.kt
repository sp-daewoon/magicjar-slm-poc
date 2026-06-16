package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL ConditionType.
 *
 * Source: parser.py:L23 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-1
 *
 * - CROSSOVER: `crosses_above` · `crosses_below` operator
 * - THRESHOLD: 비교 operator (`>`·`<`·`>=`·`<=`·`==`·`!=`)
 * - BREAKOUT: `breaks` operator
 * - PATTERN: 좌변이 `consecutive(...)` 또는 `pattern(...)` indicator
 * - COMPOSITE: AND·OR 합성 조건
 *
 * 결정 룰: parser.py:L361-L390 (operator 분기) — pr-35-kis-dsl-raw-verification.md §2-5 LOCK.
 */
enum class ConditionType {
    CROSSOVER,
    THRESHOLD,
    BREAKOUT,
    PATTERN,
    COMPOSITE,
}
