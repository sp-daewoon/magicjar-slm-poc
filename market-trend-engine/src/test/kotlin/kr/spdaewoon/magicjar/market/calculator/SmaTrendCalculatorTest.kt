package kr.spdaewoon.magicjar.market.calculator

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.trend.CrossSignal
import kr.spdaewoon.magicjar.domain.trend.TrendLabel
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * SmaTrendCalculator MA · CrossSignal · TrendLabel 표준 정의 단위 테스트 (ADR 0100 §2-2-D).
 */
class SmaTrendCalculatorTest {
    private val calc = SmaTrendCalculator()

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** 종가만 갖는 단순 1d 캔들 fixture (open=high=low=close=adj=raw). */
    private fun candles(closes: List<Int>): List<Candle> =
        closes.mapIndexed { i, c ->
            val v = BigDecimal(c)
            Candle(
                id = null,
                code = "0001",
                interval = CandleInterval.ONE_DAY,
                ts = Instant.fromEpochSeconds(base.epochSeconds + i.toLong() * 86_400),
                openRaw = v,
                highRaw = v,
                lowRaw = v,
                closeRaw = v,
                openAdj = v,
                highAdj = v,
                lowAdj = v,
                closeAdj = v,
                volume = 100L,
                source = "TEST",
            )
        }

    @Test
    fun `sma 5개 종가 평균 정상`() {
        val closes = listOf(BigDecimal(10), BigDecimal(20), BigDecimal(30), BigDecimal(40), BigDecimal(50))
        // 1+2+3+4+5 = 150 / 5 = 30
        val sma = calc.sma(closes, 5)
        sma!!.toInt() shouldBe 30
    }

    @Test
    fun `sma period 미만 candle 시 null`() {
        val closes = listOf(BigDecimal(10), BigDecimal(20))
        calc.sma(closes, 5) shouldBe null
    }

    @Test
    fun `dataInsufficient flag candles less than longMa`() {
        val cdl = candles((1..10).toList()) // 10 < 60
        val trend = calc.computeIndexTrend("0001", cdl, listOf(5, 20, 60))
        trend.dataInsufficient shouldBe true
        trend.crossSignal shouldBe CrossSignal.NEUTRAL
        trend.trendLabel shouldBe TrendLabel.SIDEWAYS
    }

    @Test
    fun `golden cross — shortMa breaks above longMa`() {
        // long MA = 5 (period), short MA = 2
        // closes: 첫 4 봉은 모두 20 → shortMa = longMa = 20 (cross 0)
        // 5번째 봉 종가 100 → shortMa[5] = (20+100)/2 = 60 · longMa[5] = (20*4+100)/5 = 36
        // 직전 봉(4번째): shortMa[4] = (20+20)/2 = 20 · longMa[4] = (20*4)/4 → only 4 closes for period 5 = null
        // 그래서 6번째 봉부터 evaluation 가능. 한 봉 더 추가:
        // 6번째 종가 100 → shortMa = (100+100)/2 = 100 · longMa = (20*3+100*2)/5 = 32
        // 직전(5번째): shortMa = (20+100)/2 = 60 · longMa = (20*4+100)/5 = 36 → shortPrev > longPrev → no golden
        // 더 명확한 fixture:
        //  closes: [10,10,10,10,10,10] then [10,10,10,10,10,100,100]
        //  period long=5 short=2
        //  봉 6: shortMa = (10+100)/2 = 55, longMa = (10*4+100)/5 = 28 → cross? prev(봉5): shortMa=(10+10)/2=10, longMa=(10*5)/5=10 → 10<=10 AND 55>28 → GOLDEN
        val cdl = candles(listOf(10, 10, 10, 10, 10, 100))
        val trend = calc.computeIndexTrend("0001", cdl, listOf(2, 5))
        trend.crossSignal shouldBe CrossSignal.GOLDEN
    }

    @Test
    fun `dead cross — shortMa breaks below longMa`() {
        // closes: 모두 100, 마지막 봉 10 → shortMa 하향 돌파
        // [100,100,100,100,100,10] long=5, short=2
        // 봉6: shortMa = (100+10)/2 = 55, longMa = (100*4+10)/5 = 82 → cross? prev: shortMa=100, longMa=100 → 100>=100 AND 55<82 → DEAD
        val cdl = candles(listOf(100, 100, 100, 100, 100, 10))
        val trend = calc.computeIndexTrend("0001", cdl, listOf(2, 5))
        trend.crossSignal shouldBe CrossSignal.DEAD
    }

    @Test
    fun `neutral cross — no break`() {
        val cdl = candles(listOf(100, 100, 100, 100, 100, 100, 100))
        val trend = calc.computeIndexTrend("0001", cdl, listOf(2, 5))
        trend.crossSignal shouldBe CrossSignal.NEUTRAL
    }

    @Test
    fun `trend label UP — longMa 5일전 변화 0point5percent 초과 AND close above longMa`() {
        // size=20, period=3 → t=20 longMa=avg(closes[17..19]), t-5=15 longMa=avg(closes[12..14])
        // closes = [100]*15 + [110]*5 → t=20 longMa=(110+110+110)/3=110 · t-5 longMa=(100+100+100)/3=100
        // 변화율 +10% AND close(110) > longMa(110) → close > longMa 위반(equal).
        // 더 명확: [100]*15 + [111,112,113,114,115] → t=20 longMa=(113+114+115)/3=114, close=115 > 114 → UP
        val cdl = candles(List(15) { 100 } + listOf(111, 112, 113, 114, 115))
        val trend = calc.computeIndexTrend("0001", cdl, listOf(3))
        trend.trendLabel shouldBe TrendLabel.UP
    }

    @Test
    fun `trend label DOWN — longMa drop 0point5percent below AND close below longMa`() {
        val cdl = candles(List(15) { 100 } + listOf(89, 88, 87, 86, 85))
        val trend = calc.computeIndexTrend("0001", cdl, listOf(3))
        trend.trendLabel shouldBe TrendLabel.DOWN
    }

    @Test
    fun `trend label SIDEWAYS — change less than 0point5percent`() {
        val cdl = candles(List(20) { 100 })
        val trend = calc.computeIndexTrend("0001", cdl, listOf(3))
        trend.trendLabel shouldBe TrendLabel.SIDEWAYS
    }

    @Test
    fun `compute index trend echoes periodsUsed and isUserCustom`() {
        val cdl = candles(List(70) { 100 + it })
        val trend = calc.computeIndexTrend("0001", cdl, listOf(5, 20, 60), isUserCustom = true)
        trend.periodsUsed shouldBe listOf(5, 20, 60)
        trend.isUserCustom shouldBe true
        trend.dataInsufficient shouldBe false
        // 5/20/60 모두 ma map 채워짐
        trend.ma.keys shouldBe setOf(5, 20, 60)
    }

    @Test
    fun `change rate 직전 봉 대비 percent`() {
        val cdl = candles(listOf(100, 110)) // 110 → +10%
        val trend = calc.computeIndexTrend("0001", cdl, listOf(1))
        // changeRate = (110-100)/100 * 100 = 10.0000
        trend.changeRate!!.toInt() shouldBe 10
    }
}
