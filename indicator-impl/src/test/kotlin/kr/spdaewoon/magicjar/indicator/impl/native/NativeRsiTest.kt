package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * NativeRsi 정확성 검증.
 *
 * KIS calc_rsi (indicators.py:282-309) 정합 (EWM-based, not Wilder):
 *   - delta = close.diff()
 *   - avg_gain = gain.ewm(span=period, adjust=False).mean()
 *   - RSI = 100 - 100 / (1 + avg_gain/avg_loss)
 */
class NativeRsiTest {
    @Test
    fun `상승만 있는 시계열 → RSI = 100`() {
        // 모두 상승 → loss=0 → RSI=100
        val candles =
            NativeFixtures.makeCandles(
                listOf(
                    100.0,
                    110.0,
                    120.0,
                    130.0,
                    140.0,
                    150.0,
                    160.0,
                    170.0,
                    180.0,
                    190.0,
                    200.0,
                    210.0,
                    220.0,
                    230.0,
                    240.0,
                    250.0,
                ),
            )
        val result = NativeRsi.calculate(IndicatorSpec("rsi", mapOf("period" to 14)), candles)
        val last =
            result.series
                .last()
                .values["rsi"]!!
                .toDouble()
        assertThat(last).isEqualTo(100.0)
    }

    @Test
    fun `결정적 시계열 RSI in (0, 100) 범위`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeRsi.calculate(IndicatorSpec("rsi", mapOf("period" to 14)), candles)
        assertThat(result.source).isEqualTo("native")
        // 결과 series 30개 (warm-up 별도 안 함 — KIS 정합)
        assertThat(result.series).hasSize(30)
        // 모든 RSI 값 0 < rsi <= 100
        result.series.drop(14).forEach { p ->
            val v = p.values["rsi"]!!.toDouble()
            assertThat(v).isStrictlyBetween(0.0, 100.001)
        }
    }

    @Test
    fun `상하 교차 시계열 RSI 50 부근 정합`() {
        // 상승·하락 번갈아 → gain ≈ loss → RSI ≈ 50
        val closes =
            (0 until 30).map { i ->
                if (i % 2 == 0) 100.0 + (i / 2.0) else 100.0 - (i / 2.0 + 0.5)
            }
        val candles = NativeFixtures.makeCandles(closes)
        val result = NativeRsi.calculate(IndicatorSpec("rsi", mapOf("period" to 14)), candles)
        val last =
            result.series
                .last()
                .values["rsi"]!!
                .toDouble()
        // EWM RSI는 Wilder와 달라 정확히 50은 아니지만 합리적 범위 내
        assertThat(last).isBetween(20.0, 80.0)
    }

    @Test
    fun `insufficient candles → throw`() {
        val candles = NativeFixtures.makeCandles(listOf(100.0, 101.0))
        assertThatThrownBy {
            NativeRsi.calculate(IndicatorSpec("rsi", mapOf("period" to 14)), candles)
        }.isInstanceOf(IndicatorException.InsufficientCandlesException::class.java)
    }
}
