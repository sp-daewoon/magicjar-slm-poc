package kr.spdaewoon.magicjar.pattern

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.pattern.helper.ConsecutiveDirectionCalculator
import kr.spdaewoon.magicjar.pattern.helper.SimpleTripleHelper
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * CandleMath 7 helper + SimpleTripleHelper + ConsecutiveDirectionCalculator 검증.
 *
 * Source: candlestick.py L14-L36 + L71 + L562 + magicJar own.
 */
class CandleMathTest {
    private fun bd(v: Double) = BigDecimal(v)

    private fun candle(
        ts: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
    ) = Candle(
        code = "X",
        interval = CandleInterval.ONE_DAY,
        ts = Instant.fromEpochSeconds(ts),
        openRaw = bd(open),
        highRaw = bd(high),
        lowRaw = bd(low),
        closeRaw = bd(close),
        openAdj = null,
        highAdj = null,
        lowAdj = null,
        closeAdj = null,
        volume = 0L,
        source = "fixture",
    )

    @Test
    fun `body — abs(c - o)`() {
        assertEquals(bd(5.0), CandleMath.body(bd(100.0), bd(105.0)))
        assertEquals(bd(5.0), CandleMath.body(bd(105.0), bd(100.0)))
    }

    @Test
    fun `upper_shadow — high - max(o, c)`() {
        assertEquals(bd(2.0), CandleMath.upperShadow(bd(107.0), bd(100.0), bd(105.0)))
    }

    @Test
    fun `lower_shadow — min(o, c) - low`() {
        assertEquals(bd(3.0), CandleMath.lowerShadow(bd(100.0), bd(105.0), bd(97.0)))
    }

    @Test
    fun `range — h - l`() {
        assertEquals(bd(10.0), CandleMath.range(bd(105.0), bd(95.0)))
    }

    @Test
    fun `is_bullish — c gt o`() {
        assertTrue(CandleMath.isBullish(bd(100.0), bd(101.0)))
        assertFalse(CandleMath.isBullish(bd(100.0), bd(100.0)))
    }

    @Test
    fun `is_bearish — c lt o`() {
        assertTrue(CandleMath.isBearish(bd(100.0), bd(99.0)))
        assertFalse(CandleMath.isBearish(bd(100.0), bd(100.0)))
    }

    @Test
    fun `avg_body — n=10 default`() {
        val candles =
            (1..10).map { i ->
                candle(ts = i.toLong(), open = 100.0, high = 105.0, low = 95.0, close = 100.0 + i)
            }
        // bodies = 1..10, average = 5.5
        val avg = CandleMath.avgBody(candles)
        assertEquals(bd(5.5).setScale(8), avg)
    }

    @Test
    fun `avg_body — empty returns 1`() {
        assertEquals(BigDecimal.ONE, CandleMath.avgBody(emptyList()))
    }

    // ===== SimpleTripleHelper =====
    @Test
    fun `simple_triple bullish — 3 bullish monotonic increasing returns 1`() {
        val candles =
            listOf(
                candle(1, 100.0, 102.0, 99.0, 101.0),
                candle(2, 101.0, 103.0, 100.0, 102.0),
                candle(3, 102.0, 104.0, 101.0, 103.0),
            )
        assertEquals(1, SimpleTripleHelper.simpleTriple(candles, "bullish"))
    }

    @Test
    fun `simple_triple bearish — 3 bearish monotonic decreasing returns -1`() {
        val candles =
            listOf(
                candle(1, 105.0, 106.0, 102.0, 103.0),
                candle(2, 103.0, 104.0, 100.0, 101.0),
                candle(3, 101.0, 102.0, 98.0, 99.0),
            )
        assertEquals(-1, SimpleTripleHelper.simpleTriple(candles, "bearish"))
    }

    @Test
    fun `simple_triple — non-monotonic returns 0`() {
        val candles =
            listOf(
                candle(1, 100.0, 102.0, 99.0, 101.0),
                candle(2, 101.0, 103.0, 100.0, 100.5),
                candle(3, 100.0, 104.0, 99.0, 103.0),
            )
        assertEquals(0, SimpleTripleHelper.simpleTriple(candles, "bullish"))
    }

    @Test
    fun `simple_triple — fewer than 3 candles returns 0`() {
        assertEquals(0, SimpleTripleHelper.simpleTriple(emptyList(), "bullish"))
        assertEquals(0, SimpleTripleHelper.simpleTriple(listOf(candle(1, 100.0, 105.0, 99.0, 102.0)), "bullish"))
    }

    @Test
    fun `simple_triple — invalid direction returns 0`() {
        val candles =
            (1..3).map { i ->
                candle(i.toLong(), 100.0, 102.0, 99.0, 100.0 + i)
            }
        assertEquals(0, SimpleTripleHelper.simpleTriple(candles, "neutral"))
    }

    // ===== ConsecutiveDirectionCalculator =====
    @Test
    fun `consecutive up — counts bullish from end backward`() {
        val candles =
            listOf(
                candle(1, 100.0, 102.0, 99.0, 99.5), // bearish (break)
                candle(2, 100.0, 102.0, 99.0, 101.0), // bullish
                candle(3, 101.0, 103.0, 100.0, 102.0), // bullish
                candle(4, 102.0, 104.0, 101.0, 103.0), // bullish
            )
        assertEquals(3, ConsecutiveDirectionCalculator.consecutive("up", candles))
    }

    @Test
    fun `consecutive — empty returns 0`() {
        assertEquals(0, ConsecutiveDirectionCalculator.consecutive("up", emptyList()))
    }
}
