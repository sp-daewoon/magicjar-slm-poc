package kr.spdaewoon.magicjar.api.strategyresearch

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.strategyresearch.BuiltinResearchCandidates
import kr.spdaewoon.magicjar.domain.strategyresearch.CandidateEntryEvaluator
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.Instant

class StrategyResearchServiceTest {
    private val candleRepository: CandleJpaRepository = mock()

    // VOLUME_WASHOUT_REBOUND가 fixture index 26(투매 직후 양봉)에서 진입하도록 하는 stub evaluator.
    private val washoutEvaluator =
        CandidateEntryEvaluator { entryDsl, candles, index ->
            entryDsl.contains("close > open") &&
                index == 26 &&
                candles[index].close > candles[index].open
        }
    private val service =
        StrategyResearchService(
            candleRepository = candleRepository,
            candidateProvider = BuiltinResearchCandidates,
            entryEvaluator = washoutEvaluator,
            maxCandlesPerRun = 5000,
        )

    @Test
    fun `propose returns research candidates without persisting or ordering`() {
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = "038110",
                interval = "1m",
                fromTs = Instant.parse("2026-04-28T00:00:00Z"),
                toTs = Instant.parse("2026-04-28T00:44:00Z"),
            ),
        ).thenReturn(washoutEntities())

        val report =
            service.propose(
                StrategyResearchRequestDto(
                    userId = "74683223",
                    code = "038110",
                    interval = "1m",
                    from = "2026-04-28T00:00:00Z",
                    to = "2026-04-28T00:44:00Z",
                    targetProfitPct = 1.0,
                    stopLossPct = 0.8,
                    maxHoldBars = 4,
                ),
            )

        report.candleCount shouldBe 45
        report.candidates.shouldNotBeEmpty()
        report.candidates
            .first { it.key == "VOLUME_WASHOUT_REBOUND" }
            .trades
            .shouldNotBeEmpty()
    }

    @Test
    fun `propose prepends previous candles when requested window has less than minimum candles`() {
        val allCandles = washoutEntities().take(40)
        val fromTs = Instant.parse("2026-04-28T00:18:00Z")
        val toTs = Instant.parse("2026-04-28T00:39:00Z")
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = "038110",
                interval = "1m",
                fromTs = fromTs,
                toTs = toTs,
            ),
        ).thenReturn(allCandles.drop(18))
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBefore(
                code = "038110",
                interval = "1m",
                before = fromTs,
                pageable = PageRequest.of(0, 18),
            ),
        ).thenReturn(allCandles.take(18).asReversed())

        val report =
            service.propose(
                StrategyResearchRequestDto(
                    userId = "74683223",
                    code = "038110",
                    interval = "1m",
                    from = fromTs.toString(),
                    to = toTs.toString(),
                    targetProfitPct = 1.0,
                    stopLossPct = 0.8,
                    maxHoldBars = 4,
                ),
            )

        report.candleCount shouldBe 40
        report.warnings.any { it.contains("최소 40개 candle") } shouldBe false
    }

    @Test
    fun `propose expands daily candle query to full KST days`() {
        val fromTs = Instant.parse("2026-03-02T00:00:00Z")
        val toTs = Instant.parse("2026-06-02T06:30:00Z")
        whenever(
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = "038110",
                interval = "1d",
                fromTs = Instant.parse("2026-03-01T15:00:00Z"),
                toTs = Instant.parse("2026-06-02T14:59:59.999999999Z"),
            ),
        ).thenReturn(washoutEntities(interval = "1d"))

        val report =
            service.propose(
                StrategyResearchRequestDto(
                    userId = "74683223",
                    code = "038110",
                    interval = "1d",
                    from = fromTs.toString(),
                    to = toTs.toString(),
                    targetProfitPct = 1.0,
                    stopLossPct = 0.8,
                    maxHoldBars = 4,
                ),
            )

        report.candleCount shouldBe 45
        report.warnings.any { it.contains("최소 40개 candle") } shouldBe false
    }

    private fun washoutEntities(interval: String = "1m"): List<CandleJpaEntity> {
        val candles = mutableListOf<CandleJpaEntity>()
        repeat(45) { index ->
            val ts = Instant.parse("2026-04-28T00:${index.toString().padStart(2, '0')}:00Z")
            val open: String
            val high: String
            val low: String
            val close: String
            val volume: Long
            when (index) {
                25 -> {
                    open = "100.0"
                    high = "100.2"
                    low = "97.8"
                    close = "98.0"
                    volume = 3000
                }
                26 -> {
                    open = "98.0"
                    high = "99.3"
                    low = "97.9"
                    close = "99.0"
                    volume = 1800
                }
                27 -> {
                    open = "99.0"
                    high = "100.5"
                    low = "98.8"
                    close = "100.1"
                    volume = 1600
                }
                else -> {
                    open = "100.0"
                    high = "100.4"
                    low = "99.6"
                    close = "100.0"
                    volume = 1000
                }
            }
            candles += candle(ts, open, high, low, close, volume, interval = interval)
        }
        return candles
    }

    private fun candle(
        ts: Instant,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: Long,
        interval: String = "1m",
    ): CandleJpaEntity =
        CandleJpaEntity(
            code = "038110",
            interval = interval,
            ts = ts,
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            volume = volume,
            source = "TEST",
        )
}
