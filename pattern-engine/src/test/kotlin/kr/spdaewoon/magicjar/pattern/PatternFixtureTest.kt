package kr.spdaewoon.magicjar.pattern

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.pattern.detector.pair.EngulfingDetector
import kr.spdaewoon.magicjar.pattern.detector.single.DojiDetector
import kr.spdaewoon.magicjar.pattern.detector.single.HammerDetector
import kr.spdaewoon.magicjar.pattern.detector.single.HangingManDetector
import kr.spdaewoon.magicjar.pattern.detector.single.InvertedHammerDetector
import kr.spdaewoon.magicjar.pattern.detector.single.ShootingStarDetector
import kr.spdaewoon.magicjar.pattern.detector.triple.EveningStarDetector
import kr.spdaewoon.magicjar.pattern.detector.triple.MorningStarDetector
import kr.spdaewoon.magicjar.pattern.detector.triple.ThreeBlackCrowsDetector
import kr.spdaewoon.magicjar.pattern.detector.triple.ThreeWhiteSoldiersDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * R1 active 10 detector fixture 정확성 — KIS candlestick.py 본문 1:1 정합 검증.
 *
 * Source: pr-38-kis-pattern-raw-verification.md §5 LOCK + spec §1-2 DoD #15.
 *
 * R1 10 detector 모두 검증:
 * 1. doji (L81)
 * 2. hammer (L119)
 * 3. hanging_man (L132 + 추세 보강)
 * 4. inverted_hammer (L145)
 * 5. shooting_star (L157 + 추세 보강)
 * 6. engulfing (L248)
 * 7. morning_star (L451)
 * 8. evening_star (L470)
 * 9. three_white_soldiers (L489)
 * 10. three_black_crows (L502)
 */
class PatternFixtureTest {
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

    // ====== R1 #1 — Doji ======
    @Test
    fun `doji — body to range ratio less than 0_1 returns BULL`() {
        // body=0.05, range=10 → 0.005 < 0.1 → BULL
        val c = candle(open = 100.0, high = 105.0, low = 95.0, close = 100.05)
        val r = DojiDetector().detect(listOf(c))
        assertEquals(Direction.BULL, r.direction)
    }

    @Test
    fun `doji — full body returns NEUTRAL`() {
        val c = candle(open = 100.0, high = 110.0, low = 99.0, close = 109.0)
        val r = DojiDetector().detect(listOf(c))
        assertEquals(Direction.NEUTRAL, r.direction)
    }

    // ====== R1 #2 — Hammer ======
    @Test
    fun `hammer — long lower shadow, small body, tiny upper shadow returns BULL`() {
        // open=105, close=106 (bullish), low=100, high=106.2
        // body=1, lower_shadow=min(105,106)-100 = 5 (>= 2*1), upper_shadow=106.2-106=0.2 (<= 0.3*1)
        val c = candle(open = 105.0, high = 106.2, low = 100.0, close = 106.0)
        val r = HammerDetector().detect(listOf(c))
        assertEquals(Direction.BULL, r.direction)
    }

    @Test
    fun `hammer — equal shadows returns NEUTRAL`() {
        val c = candle(open = 100.0, high = 105.0, low = 95.0, close = 102.0)
        val r = HammerDetector().detect(listOf(c))
        assertEquals(Direction.NEUTRAL, r.direction)
    }

    // ====== R1 #3 — HangingMan (R1 추세 보강 포함) ======
    @Test
    fun `hanging_man — hammer shape AND prior 5 candles bull at least 3 returns BEAR`() {
        // 5 prior bullish + hammer-shaped final
        val priors =
            listOf(
                candle(ts = 1, open = 100.0, high = 102.0, low = 99.0, close = 101.0),
                candle(ts = 2, open = 101.0, high = 103.0, low = 100.0, close = 102.0),
                candle(ts = 3, open = 102.0, high = 104.0, low = 101.0, close = 103.0),
                candle(ts = 4, open = 103.0, high = 105.0, low = 102.0, close = 104.0),
                candle(ts = 5, open = 104.0, high = 106.0, low = 103.0, close = 105.0),
            )
        // Final hammer-shaped — bullish small body (1) + long lower shadow (5) + tiny upper shadow (0.2)
        // body=1, ls=5 (>= 2*1), us=0.2 (<= 0.3*1)
        val finalC = candle(ts = 6, open = 105.0, high = 106.2, low = 100.0, close = 106.0)
        val r = HangingManDetector().detect(priors + finalC)
        assertEquals(Direction.BEAR, r.direction)
    }

    @Test
    fun `hanging_man — hammer shape but no prior uptrend returns NEUTRAL`() {
        // prior 5 candles all bearish → no uptrend
        val priors =
            (1..5).map { i ->
                candle(ts = i.toLong(), open = 110.0 - i, high = 112.0 - i, low = 108.0 - i, close = 109.0 - i)
            }
        val finalC = candle(ts = 6, open = 105.0, high = 106.2, low = 100.0, close = 106.0)
        val r = HangingManDetector().detect(priors + finalC)
        assertEquals(Direction.NEUTRAL, r.direction)
    }

    // ====== R1 #4 — InvertedHammer ======
    @Test
    fun `inverted_hammer — long upper shadow, small body returns BULL`() {
        // open=100, close=101 (bullish), upper_shadow=high-max=106-101=5 (>= 2*1), lower_shadow=min-low=100-100=0 (<= 0.3*1)
        val c = candle(open = 100.0, high = 106.0, low = 100.0, close = 101.0)
        val r = InvertedHammerDetector().detect(listOf(c))
        assertEquals(Direction.BULL, r.direction)
    }

    // ====== R1 #5 — ShootingStar (R1 추세 보강 포함) ======
    @Test
    fun `shooting_star — inverted hammer shape AND prior uptrend returns BEAR`() {
        val priors =
            listOf(
                candle(ts = 1, open = 100.0, high = 102.0, low = 99.0, close = 101.0),
                candle(ts = 2, open = 101.0, high = 103.0, low = 100.0, close = 102.0),
                candle(ts = 3, open = 102.0, high = 104.0, low = 101.0, close = 103.0),
                candle(ts = 4, open = 103.0, high = 105.0, low = 102.0, close = 104.0),
                candle(ts = 5, open = 104.0, high = 106.0, low = 103.0, close = 105.0),
            )
        val finalC = candle(ts = 6, open = 105.0, high = 111.0, low = 105.0, close = 106.0)
        val r = ShootingStarDetector().detect(priors + finalC)
        assertEquals(Direction.BEAR, r.direction)
    }

    // ====== R1 #6 — Engulfing ======
    @Test
    fun `engulfing — bullish prev bearish curr bullish wraps body returns BULL`() {
        // c1 bearish (open>close), c2 bullish, o2 <= c1.close, c2.close >= c1.open
        val c1 = candle(ts = 1, open = 105.0, high = 106.0, low = 100.0, close = 101.0)
        val c2 = candle(ts = 2, open = 100.0, high = 107.0, low = 99.0, close = 106.0)
        val r = EngulfingDetector().detect(listOf(c1, c2))
        assertEquals(Direction.BULL, r.direction)
    }

    @Test
    fun `engulfing — bearish prev bullish curr bearish wraps returns BEAR`() {
        val c1 = candle(ts = 1, open = 100.0, high = 106.0, low = 99.0, close = 105.0)
        val c2 = candle(ts = 2, open = 106.0, high = 107.0, low = 98.0, close = 99.0)
        val r = EngulfingDetector().detect(listOf(c1, c2))
        assertEquals(Direction.BEAR, r.direction)
    }

    // ====== R1 #7 — MorningStar ======
    @Test
    fun `morning_star — bearish big AND small body AND bullish recovers above midpoint returns BULL`() {
        // c1 bearish big body 100→90 (body=10), mid1=(100+90)/2=95
        // c2 small body 0.5 < 10*0.3
        // c3 bullish AND close=98 > 95
        val c1 = candle(ts = 1, open = 100.0, high = 101.0, low = 89.0, close = 90.0)
        val c2 = candle(ts = 2, open = 89.0, high = 90.0, low = 87.0, close = 89.5)
        val c3 = candle(ts = 3, open = 90.0, high = 99.0, low = 89.5, close = 98.0)
        val r = MorningStarDetector().detect(listOf(c1, c2, c3))
        assertEquals(Direction.BULL, r.direction)
    }

    // ====== R1 #8 — EveningStar ======
    @Test
    fun `evening_star — bullish big AND small body AND bearish closes below midpoint returns BEAR`() {
        val c1 = candle(ts = 1, open = 90.0, high = 101.0, low = 89.0, close = 100.0)
        val c2 = candle(ts = 2, open = 100.0, high = 102.0, low = 99.0, close = 100.5)
        val c3 = candle(ts = 3, open = 99.0, high = 100.0, low = 91.0, close = 92.0)
        val r = EveningStarDetector().detect(listOf(c1, c2, c3))
        assertEquals(Direction.BEAR, r.direction)
    }

    // ====== R1 #9 — ThreeWhiteSoldiers ======
    @Test
    fun `three_white_soldiers — three bullish with monotonic increasing close returns BULL`() {
        val c1 = candle(ts = 1, open = 100.0, high = 102.0, low = 99.0, close = 101.0)
        val c2 = candle(ts = 2, open = 101.0, high = 103.0, low = 100.0, close = 102.0)
        val c3 = candle(ts = 3, open = 102.0, high = 104.0, low = 101.0, close = 103.0)
        val r = ThreeWhiteSoldiersDetector().detect(listOf(c1, c2, c3))
        assertEquals(Direction.BULL, r.direction)
    }

    @Test
    fun `three_white_soldiers — non-monotonic returns NEUTRAL`() {
        val c1 = candle(ts = 1, open = 100.0, high = 102.0, low = 99.0, close = 101.0)
        val c2 = candle(ts = 2, open = 101.0, high = 103.0, low = 100.0, close = 100.5) // close < c1.close
        val c3 = candle(ts = 3, open = 100.0, high = 104.0, low = 99.0, close = 103.0)
        val r = ThreeWhiteSoldiersDetector().detect(listOf(c1, c2, c3))
        assertEquals(Direction.NEUTRAL, r.direction)
    }

    // ====== R1 #10 — ThreeBlackCrows ======
    @Test
    fun `three_black_crows — three bearish with monotonic decreasing close returns BEAR`() {
        val c1 = candle(ts = 1, open = 105.0, high = 106.0, low = 102.0, close = 103.0)
        val c2 = candle(ts = 2, open = 103.0, high = 104.0, low = 100.0, close = 101.0)
        val c3 = candle(ts = 3, open = 101.0, high = 102.0, low = 98.0, close = 99.0)
        val r = ThreeBlackCrowsDetector().detect(listOf(c1, c2, c3))
        assertEquals(Direction.BEAR, r.direction)
    }
}
