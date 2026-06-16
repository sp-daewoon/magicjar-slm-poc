package kr.spdaewoon.magicjar.market.calculator

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * SmaTrendCalculator.computeSectorRanking 단위 테스트 (ADR 0100 §2-2-D).
 *
 * - todayTop: 등락 0.7 + 거래대금 정규화 0.3
 * - longTop: Mansfield RS rsPeriod + 우상향 fit 가산점
 */
class SectorRankingCalculatorTest {
    private val calc = SmaTrendCalculator()

    private val base: Instant = Instant.parse("2026-05-24T10:00:00Z")

    private fun snap(
        code: String,
        change: Double,
        trade: Long? = 1_000_000L,
    ): SectorSnapshot =
        SectorSnapshot(
            sectorCode = code,
            sectorName = "섹터 $code",
            ts = base,
            price = BigDecimal(1000),
            changeRate = BigDecimal(change.toString()),
            tradeValue = trade?.let { BigDecimal(it) },
        )

    private val cdlBase: Instant = Instant.parse("2026-04-01T00:00:00Z")

    private fun candles(
        code: String,
        closes: List<Int>,
    ): List<Candle> =
        closes.mapIndexed { i, c ->
            val v = BigDecimal(c)
            Candle(
                id = null,
                code = code,
                interval = CandleInterval.ONE_DAY,
                ts = Instant.fromEpochSeconds(cdlBase.epochSeconds + i.toLong() * 86_400),
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
    fun `empty snapshots 면 empty ranking`() {
        val ranking =
            calc.computeSectorRanking(
                snapshots = emptyList(),
                baselineSnapshots = null,
                sectorDailyCandles = emptyMap(),
                baselineCandles = emptyList(),
                periods = listOf(5, 20, 60),
                rsPeriod = 60,
            )
        ranking.todayTop shouldHaveSize 0
        ranking.longTop shouldHaveSize 0
        ranking.periodsUsed shouldBe listOf(5, 20, 60)
        ranking.rsPeriod shouldBe 60
    }

    @Test
    fun `todayTop 등락률 내림차순 정렬 (baseline trade 없음)`() {
        val snapshots = listOf(snap("A", 3.0), snap("B", 5.0), snap("C", 1.0))
        val ranking =
            calc.computeSectorRanking(
                snapshots = snapshots,
                baselineSnapshots = null,
                sectorDailyCandles = emptyMap(),
                baselineCandles = emptyList(),
                periods = listOf(5),
                rsPeriod = 5,
                topN = 3,
            )
        ranking.todayTop.map { it.sectorCode } shouldBe listOf("B", "A", "C")
    }

    @Test
    fun `topN 제한`() {
        val snapshots = (1..10).map { snap("S$it", it.toDouble()) }
        val ranking =
            calc.computeSectorRanking(
                snapshots = snapshots,
                baselineSnapshots = null,
                sectorDailyCandles = emptyMap(),
                baselineCandles = emptyList(),
                periods = listOf(5),
                rsPeriod = 5,
                topN = 5,
            )
        ranking.todayTop shouldHaveSize 5
    }

    @Test
    fun `longTop Mansfield RS — sector ratio 가 KOSPI ratio 보다 큰 sector 우위`() {
        // KOSPI = baseline: 모두 100 (변화 0)
        // sector A: 모두 100 → RS = 1.0
        // sector B: 100 → 200 (50% 상승) → RS > 1.0
        // sector C: 100 → 50 (50% 하락) → RS < 1.0
        // rsPeriod = 5 (5일 전 vs 현재)
        val baselineCdl = candles("KOSPI", List(10) { 100 })
        val sectorA = candles("A", List(10) { 100 })
        val sectorB = candles("B", List(5) { 100 } + List(5) { 200 })
        val sectorC = candles("C", List(5) { 100 } + List(5) { 50 })

        val snapshots = listOf(snap("A", 0.0), snap("B", 50.0), snap("C", -50.0))
        val ranking =
            calc.computeSectorRanking(
                snapshots = snapshots,
                baselineSnapshots = null,
                sectorDailyCandles = mapOf("A" to sectorA, "B" to sectorB, "C" to sectorC),
                baselineCandles = baselineCdl,
                periods = listOf(3),
                rsPeriod = 5,
                topN = 3,
            )
        // B가 RS 가장 높음
        ranking.longTop.first().sectorCode shouldBe "B"
        // C는 RS 가장 낮음
        ranking.longTop.last().sectorCode shouldBe "C"
    }

    @Test
    fun `longTop — baselineCandles 미충분 시 empty`() {
        val ranking =
            calc.computeSectorRanking(
                snapshots = listOf(snap("A", 5.0)),
                baselineSnapshots = null,
                sectorDailyCandles = mapOf("A" to candles("A", List(10) { 100 })),
                baselineCandles = candles("KOSPI", listOf(100, 100)), // 2 < rsPeriod+1=6
                periods = listOf(3),
                rsPeriod = 5,
            )
        ranking.longTop shouldHaveSize 0
    }

    @Test
    fun `isUserCustom flag echo`() {
        val ranking =
            calc.computeSectorRanking(
                snapshots = emptyList(),
                baselineSnapshots = null,
                sectorDailyCandles = emptyMap(),
                baselineCandles = emptyList(),
                periods = listOf(5, 20),
                rsPeriod = 30,
                isUserCustom = true,
            )
        ranking.isUserCustom shouldBe true
        ranking.rsPeriod shouldBe 30
    }
}
