package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * NativeMa 정확성 검증.
 *
 * KIS calc_ma `df["close"].rolling(window=period).mean()` 정합:
 *   - period=5, closes=[100,102,101,103,105]: 첫 ma(t=4) = (100+102+101+103+105)/5 = 102.2
 *   - period=5, closes[5]=104: ma(t=5) = (102+101+103+105+104)/5 = 103.0
 */
class NativeMaTest {
    @Test
    fun `5일 SMA 정합 검증`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeMa.calculate(IndicatorSpec("ma", mapOf("period" to 5)), candles)

        assertThat(result.source).isEqualTo("native")
        // 첫 4개 candle은 warm-up (period=5라 5번째부터)
        assertThat(result.series).hasSize(NativeFixtures.DETERMINISTIC_CLOSES.size - 4)
        // 첫 SMA = (100+102+101+103+105)/5 = 102.2
        val first = result.series[0].values["ma"]!!
        assertThat(first).isEqualByComparingTo(BigDecimal("102.20000000"))
        // 두 번째 SMA = (102+101+103+105+104)/5 = 103.0
        val second = result.series[1].values["ma"]!!
        assertThat(second).isEqualByComparingTo(BigDecimal("103.00000000"))
    }

    @Test
    fun `period 20 SMA 정합 검증`() {
        val candles = NativeFixtures.makeCandles(NativeFixtures.DETERMINISTIC_CLOSES)
        val result = NativeMa.calculate(IndicatorSpec("ma", mapOf("period" to 20)), candles)
        // closes[0..19] = 100..120 sum = 2210. 첫 SMA = 110.5
        // (closes 합: 100+102+101+103+105+104+106+108+107+109+111+110+112+114+113+115+117+116+118+120 = 2191)
        // 정확 합 2191/20 = 109.55
        assertThat(result.series).isNotEmpty
        val expectedFirst = BigDecimal("109.55000000")
        val actualFirst =
            result.series[0]
                .values["ma"]!!
                .setScale(8, RoundingMode.HALF_EVEN)
        assertThat(actualFirst).isEqualByComparingTo(expectedFirst)
    }

    @Test
    fun `insufficient candles → throw`() {
        val candles = NativeFixtures.makeCandles(listOf(100.0, 101.0, 102.0))
        assertThatThrownBy {
            NativeMa.calculate(IndicatorSpec("ma", mapOf("period" to 5)), candles)
        }.isInstanceOf(IndicatorException.InsufficientCandlesException::class.java)
    }
}
