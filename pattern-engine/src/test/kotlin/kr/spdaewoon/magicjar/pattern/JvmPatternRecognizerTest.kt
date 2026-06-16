package kr.spdaewoon.magicjar.pattern

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.PatternCatalog
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.detector.pair.EngulfingDetector
import kr.spdaewoon.magicjar.pattern.detector.single.DojiDetector
import kr.spdaewoon.magicjar.pattern.detector.single.HammerDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * JvmPatternRecognizer 통합 검증 — recognize multi-pattern · 캔들 부족 가드 · 미지원 패턴 graceful · consecutive helper.
 *
 * spec §1-2 DoD #1·#3·#5·#7·#9 정합.
 */
class JvmPatternRecognizerTest {
    private fun candle(
        ts: Long = 0L,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
    ): Candle =
        Candle(
            code = "005930",
            interval = CandleInterval.ONE_DAY,
            ts = Instant.fromEpochSeconds(ts),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1000L,
            source = "fixture",
        )

    /**
     * 57 detector full collection — `assertEquals(57, detectors.size)` GREEN.
     */
    private fun fullCatalogDetectors(): List<PatternDetector> =
        PatternCatalog.ALL.map { spec ->
            object : PatternDetector {
                override val id = spec.id

                override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
            }
        }

    @Test
    fun `init — detectors size mismatch throws`() {
        val partial = fullCatalogDetectors().take(56)
        assertThrows(IllegalArgumentException::class.java) {
            JvmPatternRecognizer(partial)
        }
    }

    @Test
    fun `init — 57 detectors registers cleanly`() {
        val recognizer = JvmPatternRecognizer(fullCatalogDetectors())
        assertEquals(0, recognizer.recognize(emptyList(), setOf("doji")).size)
    }

    @Test
    fun `recognize — fewer than 5 candles returns empty list (KIS L48)`() {
        val detectors = mutableListOf<PatternDetector>()
        detectors.add(DojiDetector())
        // pad to 57 with stubs (using catalog ids minus doji)
        PatternCatalog.ALL.filter { it.id != "doji" }.forEach { spec ->
            detectors.add(
                object : PatternDetector {
                    override val id = spec.id

                    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
                },
            )
        }
        val recognizer = JvmPatternRecognizer(detectors)
        val candles = (1..4).map { candle(ts = it.toLong(), open = 100.0, high = 105.0, low = 95.0, close = 100.0) }
        assertEquals(emptyList<Any>(), recognizer.recognize(candles, setOf("doji")))
    }

    @Test
    fun `recognize — unknown pattern id returns empty list (KIS L52 graceful)`() {
        val recognizer = JvmPatternRecognizer(fullCatalogDetectors())
        val candles = (1..6).map { candle(ts = it.toLong(), open = 100.0, high = 105.0, low = 95.0, close = 100.0) }
        assertEquals(emptyList<Any>(), recognizer.recognize(candles, setOf("nonexistent_pattern")))
    }

    @Test
    fun `recognize — multiple patterns returns combined matches`() {
        val detectors = mutableListOf<PatternDetector>()
        detectors.add(DojiDetector())
        detectors.add(HammerDetector())
        detectors.add(EngulfingDetector())
        PatternCatalog.ALL.filter { it.id !in setOf("doji", "hammer", "engulfing") }.forEach { spec ->
            detectors.add(
                object : PatternDetector {
                    override val id = spec.id

                    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
                },
            )
        }
        val recognizer = JvmPatternRecognizer(detectors)
        // doji-shaped final + 4 prior padding
        val candles =
            listOf(
                candle(ts = 1, open = 100.0, high = 105.0, low = 95.0, close = 100.0),
                candle(ts = 2, open = 100.0, high = 105.0, low = 95.0, close = 100.0),
                candle(ts = 3, open = 100.0, high = 105.0, low = 95.0, close = 100.0),
                candle(ts = 4, open = 100.0, high = 105.0, low = 95.0, close = 100.0),
                candle(ts = 5, open = 100.0, high = 105.0, low = 95.0, close = 100.05), // doji
            )
        val matches = recognizer.recognize(candles, setOf("doji", "hammer", "engulfing"))
        // doji should match (BULL), others NEUTRAL or no-match
        assertTrue(matches.any { it.pattern == "doji" })
    }

    @Test
    fun `recognize — Boolean overload returns true when matched`() {
        val recognizer = JvmPatternRecognizer(fullCatalogDetectors().filter { it.id != "doji" } + DojiDetector())
        val candles = (1..5).map { candle(ts = it.toLong(), open = 100.0, high = 105.0, low = 95.0, close = 100.05) }
        assertTrue(recognizer.recognize("doji", candles))
        assertFalse(recognizer.recognize("nonexistent_pattern", candles))
    }

    @Test
    fun `consecutive — 3 bullish in a row returns 3`() {
        val recognizer = JvmPatternRecognizer(fullCatalogDetectors())
        val candles =
            listOf(
                candle(ts = 1, open = 100.0, high = 105.0, low = 99.0, close = 100.0), // not bullish (close==open)
                candle(ts = 2, open = 100.0, high = 102.0, low = 99.0, close = 101.0), // bullish
                candle(ts = 3, open = 101.0, high = 103.0, low = 100.0, close = 102.0), // bullish
                candle(ts = 4, open = 102.0, high = 104.0, low = 101.0, close = 103.0), // bullish
            )
        assertEquals(3, recognizer.consecutive("up", candles))
    }

    @Test
    fun `consecutive — 0 when last candle is bearish`() {
        val recognizer = JvmPatternRecognizer(fullCatalogDetectors())
        val candles =
            listOf(
                candle(ts = 1, open = 100.0, high = 105.0, low = 99.0, close = 99.0), // bearish
            )
        assertEquals(0, recognizer.consecutive("up", candles))
    }

    @Test
    fun `consecutive — invalid direction returns 0`() {
        val recognizer = JvmPatternRecognizer(fullCatalogDetectors())
        assertEquals(0, recognizer.consecutive("sideways", listOf(candle(open = 100.0, high = 102.0, low = 99.0, close = 101.0))))
    }
}
