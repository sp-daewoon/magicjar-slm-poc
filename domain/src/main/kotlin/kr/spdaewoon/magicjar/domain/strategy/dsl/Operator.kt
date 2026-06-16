package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL Operator (9종).
 *
 * Source: parser.py:L34 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-1
 *
 * 비교 operator 6종 + crossover 2종 + breakout 1종 = 9종.
 */
enum class Operator(
    val symbol: String,
) {
    GT(">"),
    LT("<"),
    GTE(">="),
    LTE("<="),
    EQ("=="),
    NEQ("!="),
    CROSSES_ABOVE("crosses_above"),
    CROSSES_BELOW("crosses_below"),
    BREAKS("breaks"),
    ;

    companion object {
        fun fromSymbol(symbol: String): Operator? = entries.firstOrNull { it.symbol == symbol }
    }
}
