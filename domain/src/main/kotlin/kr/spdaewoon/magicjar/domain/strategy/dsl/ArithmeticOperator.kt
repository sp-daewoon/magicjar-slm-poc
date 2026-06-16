package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL ArithmeticOperator (4종).
 *
 * Source: parser.py:L52 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-1
 */
enum class ArithmeticOperator(
    val symbol: String,
) {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
}
