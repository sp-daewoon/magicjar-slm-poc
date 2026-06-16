package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.indicator.IndicatorCatalog
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * DSL 식별자 → [IndicatorSpec] 매핑.
 *
 * Source: parser.py:L72-L107 (KIS DSL 식별자) · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §3 + spec §5-5 LOCK (자체 추론 0).
 *
 * ### 매핑 룰
 * - 1:1 매핑 (80지표): `ma` · `ema` · `rsi` · ... → IndicatorSpec(name = ref.name, params = paramsOf(ref))
 * - DSL alias (multi-output): `bb_upper` · `bb_lower` · `bb_middle` → IndicatorSpec(name = "bb", outputKey = "upper" | "lower" | "middle")
 * - DSL alias (multi-output): `macd_signal` · `macd_hist` → IndicatorSpec(name = "macd", outputKey = "signal" | "histogram")
 * - DSL alias: `stoch_k` · `stoch_d` → IndicatorSpec(name = "stochastic_k" | "stochastic_d")
 *
 * 알 수 없는 식별자는 [StrategyException.UnknownDslSymbolException] (KisYamlDslParser 화이트리스트 통과 후에도 안전망).
 */
@Component
class DslIdentifierMapper {
    /**
     * IndicatorRef → IndicatorSpec 변환.
     *
     * @throws StrategyException.UnknownDslSymbolException IndicatorEngine 등록 0건 식별자
     */
    fun toIndicatorSpec(ref: IndicatorRef): IndicatorSpec =
        when (ref.name) {
            // ===== DSL alias — multi-output =====
            "macd_signal", "macd_hist" -> IndicatorSpec("macd", paramsOfMacd(ref))
            "bb_upper", "bb_lower" -> IndicatorSpec("bb", paramsOfBb(ref))
            "bb_middle" -> IndicatorSpec("bb", mapOf("period" to (ref.params.firstOrNull()?.toIntOrDefault(20) ?: 20)))
            "stoch_k" -> IndicatorSpec("stochastic_k", paramsOfPeriod(ref, default = 14))
            "stoch_d" -> IndicatorSpec("stochastic_d", paramsOfPeriod(ref, default = 14))
            "fvg" -> IndicatorSpec("fvg", paramsOfFvg(ref))
            "order_block" -> IndicatorSpec("order_block", paramsOfOrderBlock(ref))
            in FVG_ALIASES -> IndicatorSpec("fvg", paramsOfFvg(ref))
            in ORDER_BLOCK_ALIASES -> IndicatorSpec("order_block", paramsOfOrderBlock(ref))
            // ===== 1:1 매핑 (80지표 — IndicatorCatalog 등록 정합) =====
            else -> {
                if (ref.name !in CATALOG_IDS) {
                    throw StrategyException.UnknownDslSymbolException(ref.name)
                }
                IndicatorSpec(ref.name, paramsOf(ref))
            }
        }

    /**
     * IndicatorResult → 마지막 시점 BigDecimal 추출.
     *
     * multi-output indicator는 outputKey 기반 추출 (예: `bb_upper` → values["upper"]).
     */
    fun extractValue(
        result: IndicatorResult,
        ref: IndicatorRef,
    ): BigDecimal {
        val outputKey =
            when (ref.name) {
                "macd_signal" -> "signal"
                "macd_hist" -> "histogram"
                "bb_upper" -> "upper"
                "bb_lower" -> "lower"
                "bb_middle" -> "middle"
                "stoch_k" -> "k"
                "stoch_d" -> "d"
                in FVG_ALIASES -> FVG_ALIASES.getValue(ref.name)
                in ORDER_BLOCK_ALIASES -> ORDER_BLOCK_ALIASES.getValue(ref.name)
                else -> ref.name
            }
        val lastPoint: IndicatorPoint =
            result.series.lastOrNull()
                ?: throw IllegalStateException("Empty IndicatorResult series for ${ref.name}")
        return lastPoint.values[outputKey]
            ?: lastPoint.values.values.firstOrNull()
            ?: throw IllegalStateException("Output key '$outputKey' not found for ${ref.name} (available: ${lastPoint.values.keys})")
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun paramsOf(ref: IndicatorRef): Map<String, Any> {
        val first = ref.params.firstOrNull() ?: return emptyMap()
        return mapOf("period" to first.toIntOrDefault(20))
    }

    private fun paramsOfPeriod(
        ref: IndicatorRef,
        default: Int,
    ): Map<String, Any> =
        mapOf(
            "period" to (ref.params.firstOrNull()?.toIntOrDefault(default) ?: default),
        )

    private fun paramsOfMacd(ref: IndicatorRef): Map<String, Any> {
        val fast = ref.params.getOrNull(0)?.toIntOrDefault(12) ?: 12
        val slow = ref.params.getOrNull(1)?.toIntOrDefault(26) ?: 26
        val signal = ref.params.getOrNull(2)?.toIntOrDefault(9) ?: 9
        return mapOf("fast" to fast, "slow" to slow, "signal" to signal)
    }

    private fun paramsOfBb(ref: IndicatorRef): Map<String, Any> {
        val period = ref.params.getOrNull(0)?.toIntOrDefault(20) ?: 20
        val std = ref.params.getOrNull(1)?.toBigDecimalOrDefault(BigDecimal("2")) ?: BigDecimal("2")
        return mapOf("period" to period, "std" to std)
    }

    private fun paramsOfFvg(ref: IndicatorRef): Map<String, Any> {
        val mitigationPct = ref.params.getOrNull(0)?.toBigDecimalOrDefault(BigDecimal("50")) ?: BigDecimal("50")
        return mapOf("mitigationPct" to mitigationPct)
    }

    private fun paramsOfOrderBlock(ref: IndicatorRef): Map<String, Any> {
        val swingLookback = ref.params.getOrNull(0)?.toIntOrDefault(20) ?: 20
        val searchLookback = ref.params.getOrNull(1)?.toIntOrDefault(20) ?: 20
        val mitigationPct = ref.params.getOrNull(2)?.toBigDecimalOrDefault(BigDecimal("50")) ?: BigDecimal("50")
        // position 3 = detection ∈ {"bos"(default), "engulfing"}, position 4 = range ∈ {"wick"(default), "body"}
        // 위치 직렬화 계약 (frontend serializer Object.values().join) 정합 — add-only.
        val detection = ref.params.getOrNull(3)?.toEnumOrDefault(setOf("bos", "engulfing"), "bos") ?: "bos"
        val range = ref.params.getOrNull(4)?.toEnumOrDefault(setOf("wick", "body"), "wick") ?: "wick"
        return mapOf(
            "swingLookback" to swingLookback,
            "searchLookback" to searchLookback,
            "mitigationPct" to mitigationPct,
            "detection" to detection,
            "range" to range,
        )
    }

    private fun Any.toIntOrDefault(default: Int): Int =
        when (this) {
            is Int -> this
            is Number -> toInt()
            is String -> toIntOrNull() ?: default
            else -> default
        }

    private fun Any.toBigDecimalOrDefault(default: BigDecimal): BigDecimal =
        when (this) {
            is BigDecimal -> this
            is Number -> BigDecimal.valueOf(toDouble())
            is String -> toBigDecimalOrNull() ?: default
            else -> default
        }

    /**
     * enum-like 문자열 파라미터 검증. allowed set(소문자) 비포함·비문자열 → default fallback.
     */
    private fun Any.toEnumOrDefault(
        allowed: Set<String>,
        default: String,
    ): String {
        val normalized = (this as? String)?.lowercase() ?: return default
        return if (normalized in allowed) normalized else default
    }

    companion object {
        /**
         * 80지표 ID set (IndicatorCatalog.ALL.id) — 빠른 lookup.
         */
        private val CATALOG_IDS: Set<String> = IndicatorCatalog.ALL.map { it.id }.toSet()
        private val FVG_ALIASES: Map<String, String> =
            mapOf(
                "fvg_active" to "fvg",
                "fvg_bullish_active" to "bullish_active",
                "fvg_bearish_active" to "bearish_active",
                "fvg_bullish_top" to "bullish_top",
                "fvg_bullish_bottom" to "bullish_bottom",
                "fvg_bullish_mid" to "bullish_mid",
                "fvg_bullish_fill_pct" to "bullish_fill_pct",
                "fvg_bearish_top" to "bearish_top",
                "fvg_bearish_bottom" to "bearish_bottom",
                "fvg_bearish_mid" to "bearish_mid",
                "fvg_bearish_fill_pct" to "bearish_fill_pct",
            )
        private val ORDER_BLOCK_ALIASES: Map<String, String> =
            mapOf(
                "ob_active" to "order_block",
                "ob_bullish_active" to "bullish_active",
                "ob_bearish_active" to "bearish_active",
                "ob_bullish_top" to "bullish_top",
                "ob_bullish_bottom" to "bullish_bottom",
                "ob_bullish_mid" to "bullish_mid",
                "ob_bullish_fill_pct" to "bullish_fill_pct",
                "ob_bearish_top" to "bearish_top",
                "ob_bearish_bottom" to "bearish_bottom",
                "ob_bearish_mid" to "bearish_mid",
                "ob_bearish_fill_pct" to "bearish_fill_pct",
                "ob_bos_direction" to "bos_direction",
            )
    }
}
