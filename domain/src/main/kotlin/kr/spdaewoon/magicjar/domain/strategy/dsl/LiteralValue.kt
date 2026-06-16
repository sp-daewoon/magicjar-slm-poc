package kr.spdaewoon.magicjar.domain.strategy.dsl

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * KIS Strategy Builder DSL Value → [LiteralValue] rename.
 *
 * Source: parser.py:L218 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 *
 * 텍스트 표현식 `30`·`-3.5`·`5%` → LiteralValue.
 *
 * - `5%` (isPercent=true) → 평가 시 5 / 100 = 0.05 (parser.py:L228 정합)
 * - 일반 숫자 (isPercent=false) → 그대로
 *
 * BigDecimal scale=8 (T-IND IndicatorPoint 정합). spec §8-3 정합.
 */
data class LiteralValue(
    val value: BigDecimal,
    val isPercent: Boolean = false,
) : DslNode() {
    /**
     * isPercent 시 / 100 변환 — `value.divide(100, 8, HALF_EVEN)`.
     *
     * Source: parser.py:L228.
     */
    fun normalized(): BigDecimal =
        if (isPercent) {
            value.divide(BigDecimal(100), 8, RoundingMode.HALF_EVEN)
        } else {
            value
        }
}
