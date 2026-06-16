package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL Indicator → [IndicatorRef] rename.
 *
 * Source: parser.py:L60 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 *
 * 텍스트 표현식 `ma(5)` → `IndicatorRef(name="ma", params=[5])`.
 *
 * params는 KIS Python 정합 — `Any` 리스트 (정수·소수·문자열 가능).
 * 식별자 → IndicatorSpec 매핑은 `DslIdentifierMapper` (strategy-engine 모듈) 책임.
 */
data class IndicatorRef(
    val name: String,
    val params: List<Any> = emptyList(),
) : DslNode() {
    /**
     * KIS `Indicator.get_required_days()` port (parser.py:L290-L292).
     *
     * - MACD: slow + signal 합 (period 부족 가드)
     * - default: params 첫 번째 정수 (period)
     * - 0건: 0 반환
     */
    fun requiredDays(): Int =
        when (name) {
            "macd", "macd_signal", "macd_hist" -> {
                val slow = (params.getOrNull(1) as? Number)?.toInt() ?: 26
                val signal = (params.getOrNull(2) as? Number)?.toInt() ?: 9
                slow + signal
            }
            else -> (params.firstOrNull() as? Number)?.toInt() ?: 0
        }
}
