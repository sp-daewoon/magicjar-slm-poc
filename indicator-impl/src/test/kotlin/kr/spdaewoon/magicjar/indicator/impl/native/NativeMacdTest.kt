package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * NativeMacd 정확성 검증.
 *
 * KIS calc_macd (indicators.py:312-389) 정합:
 *   - macd = EMA(fast) - EMA(slow), 기본 fast=12, slow=26, signal=9
 *   - signal = EMA(macd, signal_period)
 *   - histogram = macd - signal
 */
class NativeMacdTest {
    @Test
    fun `MACD multi-output 3 keys 정합`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeMacd.calculate(IndicatorSpec("macd", mapOf("fast" to 12, "slow" to 26, "signal" to 9)), candles)

        assertThat(result.source).isEqualTo("native")
        assertThat(result.series).isNotEmpty

        // 모든 포인트 3 keys 보유 — spec §5-2 multi-output 매트릭스 정합
        result.series.forEach { p ->
            assertThat(p.values.keys).containsExactlyInAnyOrder("macd", "signal", "histogram")
        }

        // histogram = macd - signal (drift 0.0001 미만)
        result.series.forEach { p ->
            val macd = p.values["macd"]!!.toDouble()
            val signal = p.values["signal"]!!.toDouble()
            val histogram = p.values["histogram"]!!.toDouble()
            assertThat(histogram).isCloseTo(macd - signal, within(1e-6))
        }
    }

    @Test
    fun `상승 시계열 MACD positive`() {
        val candles = NativeFixtures.makeCandles((0 until 40).map { 100.0 + it * 2.0 })
        val result = NativeMacd.calculate(IndicatorSpec("macd", emptyMap()), candles)
        // 단조 상승 → fast EMA > slow EMA → macd > 0 (warm-up 이후)
        val lastMacd =
            result.series
                .last()
                .values["macd"]!!
                .toDouble()
        assertThat(lastMacd).isPositive()
    }
}
