package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * NativeEma 정확성 검증.
 *
 * KIS calc_ema `ewm(span=period, adjust=False).mean()` 정합:
 *   - α = 2 / (period + 1) = 2/13 ≈ 0.1538461538 (period=12)
 *   - EMA_0 = closes[0], EMA_t = α * x_t + (1-α) * EMA_{t-1}
 */
class NativeEmaTest {
    @Test
    fun `12일 EMA 정합 검증 — 결정적 시계열`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeEma.calculate(IndicatorSpec("ema", mapOf("period" to 12)), candles)

        assertThat(result.source).isEqualTo("native")
        // 결과 size = 30 (warm-up 별도 처리 안 함 — KIS 정합)
        assertThat(result.series).hasSize(30)

        // EMA_0 = 100.0
        assertThat(result.series[0].values["ema"]!!).isEqualByComparingTo(BigDecimal("100.00000000"))

        // EMA_1 = α * 102 + (1-α) * 100, α = 2/13
        val alpha = 2.0 / 13.0
        val ema1Expected = alpha * 102.0 + (1 - alpha) * 100.0
        val ema1Actual = result.series[1].values["ema"]!!.toDouble()
        assertThat(ema1Actual).isCloseTo(
            ema1Expected,
            org.assertj.core.api.Assertions
                .within(1e-7),
        )

        // 마지막 30개 EMA. recursive 검증 — drift 0.001% 미만
        var ema = 100.0
        NativeFixtures.DETERMINISTIC_CLOSES.forEachIndexed { i, c ->
            ema = if (i == 0) c else alpha * c + (1 - alpha) * ema
        }
        val emaLastActual =
            result.series
                .last()
                .values["ema"]!!
                .toDouble()
        assertThat(emaLastActual).isCloseTo(
            ema,
            org.assertj.core.api.Assertions
                .within(1e-6),
        )
    }

    @Test
    fun `EMA scale 8 자리 정합`() {
        val candles = NativeFixtures.makeCandles(listOf(100.0, 110.0, 120.0, 130.0, 140.0))
        val result = NativeEma.calculate(IndicatorSpec("ema", mapOf("period" to 3)), candles)
        result.series.forEach { p ->
            assertThat(p.values["ema"]!!.scale()).isEqualTo(8)
        }
    }
}
