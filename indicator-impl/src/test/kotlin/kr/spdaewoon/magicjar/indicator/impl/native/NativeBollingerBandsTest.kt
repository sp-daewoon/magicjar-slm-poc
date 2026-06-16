package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * NativeBollingerBands 정확성 검증.
 *
 * KIS calc_bb_* (indicators.py:392-487) 정합:
 *   - middle = SMA(close, period)
 *   - std = sample std (ddof=1) over rolling window
 *   - upper = middle + std * std_dev
 *   - lower = middle - std * std_dev
 */
class NativeBollingerBandsTest {
    @Test
    fun `BB multi-output 3 keys 정합`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeBollingerBands.calculate(IndicatorSpec("bb", mapOf("period" to 20, "std_dev" to 2.0)), candles)

        assertThat(result.source).isEqualTo("native")
        // 30 - 19 = 11 포인트 (warm-up trimmed)
        assertThat(result.series).hasSize(11)
        // 모든 포인트 upper·middle·lower 3 keys (§5-2 정합)
        result.series.forEach { p ->
            assertThat(p.values.keys).containsExactlyInAnyOrder("upper", "middle", "lower")
        }
    }

    @Test
    fun `BB upper-lower = 2 * std_dev * std`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeBollingerBands.calculate(IndicatorSpec("bb", mapOf("period" to 20, "std_dev" to 2.0)), candles)
        result.series.forEach { p ->
            val upper = p.values["upper"]!!.toDouble()
            val middle = p.values["middle"]!!.toDouble()
            val lower = p.values["lower"]!!.toDouble()
            // upper - middle = middle - lower
            assertThat(upper - middle).isCloseTo(middle - lower, within(1e-6))
            // upper > middle > lower
            assertThat(upper).isGreaterThan(middle)
            assertThat(middle).isGreaterThan(lower)
        }
    }

    @Test
    fun `BB middle = SMA 정합`() {
        // closes[0..19] sum = 100+102+101+103+105+104+106+108+107+109+111+110+112+114+113+115+117+116+118+120 = 2191
        // 첫 SMA(20) = 109.55
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeBollingerBands.calculate(IndicatorSpec("bb", mapOf("period" to 20)), candles)
        val firstMiddle = result.series[0].values["middle"]!!
        assertThat(firstMiddle).isEqualByComparingTo(BigDecimal("109.55000000"))
    }
}
