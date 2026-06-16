package kr.spdaewoon.magicjar.domain.screener

import java.math.BigDecimal

/**
 * Screener 매칭 종목의 multi-criteria 4 기준 점수 분해 (ADR 0071 §2-5 E-1 + spec §2-2).
 *
 * fixed weights:
 * - signal 0.40 — DSL evalResult.strength (BUY 강도 0~1)
 * - volume 0.30 — log(avgVolume5d * close) normalize (유동성)
 * - atr 0.20 — ATR/close ratio sweet spot (0.01~0.08 변동성)
 * - cap 0.10 — log(marketCap) normalize (시총 안정성)
 *
 * `total` = signal*0.40 + volume*0.30 + atr*0.20 + cap*0.10 (0~1 normalize)
 *
 * Phase 2 REST 응답 `scoreComponents: Map<String, BigDecimal>` 시리얼화.
 */
data class ScreenerScoreBreakdown(
    val signal: BigDecimal,
    val volume: BigDecimal,
    val atr: BigDecimal,
    val cap: BigDecimal,
    val total: BigDecimal,
) {
    /** REST 응답 `scoreComponents` Map 변환. */
    fun toComponentMap(): Map<String, BigDecimal> =
        mapOf(
            "signal" to signal,
            "volume" to volume,
            "atr" to atr,
            "cap" to cap,
        )

    companion object {
        /** fixed weights 4 기준 (ADR 0071 §2-5 E-1 LOCK). */
        val WEIGHT_SIGNAL: BigDecimal = BigDecimal("0.40")
        val WEIGHT_VOLUME: BigDecimal = BigDecimal("0.30")
        val WEIGHT_ATR: BigDecimal = BigDecimal("0.20")
        val WEIGHT_CAP: BigDecimal = BigDecimal("0.10")
    }
}
