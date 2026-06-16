package kr.spdaewoon.magicjar.api.screener

import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverPresetDto
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverResultDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerMatchDto
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaEntity
import kr.spdaewoon.magicjar.api.persistence.screener.DiscoveryScorecardJpaRepository
import kr.spdaewoon.magicjar.api.service.screener.DaytradeDiscoverService
import kr.spdaewoon.magicjar.api.service.screener.DiscoveryScorecardScoringService
import kr.spdaewoon.magicjar.api.service.screener.DiscoveryScorecardSnapshotService
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateDefinition
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * 발굴 스코어카드 snapshot·scoring 서비스 단위 테스트 (Flyway V77).
 *
 * 검증:
 * 1. snapshot 멱등 — discover TOP N → insertIgnore 호출(ON CONFLICT DO NOTHING은 repo 레벨 멱등).
 * 2. 채점 계산 — next_return_pct = (다음날 종가/base_close - 1)*100, scored_at 마킹.
 * 3. 미채점 skip — 대상 일봉 미적재 시 scored_at 유지(NULL) + saveAll 미호출.
 */
class DiscoveryScorecardServiceTest {
    private val kst = ZoneId.of("Asia/Seoul")

    private val discoverService: DaytradeDiscoverService = mock()
    private val candidateProvider: ResearchCandidateProvider = mock()
    private val scorecardRepository: DiscoveryScorecardJpaRepository = mock()
    private val candleRepository: CandleRepository = mock()

    @Suppress("UNCHECKED_CAST")
    private fun candleProvider(): ObjectProvider<CandleRepository> =
        (mock<ObjectProvider<CandleRepository>>()).also {
            whenever(it.ifAvailable).thenReturn(candleRepository)
        }

    private fun preset(key: String) =
        ResearchCandidateDefinition(
            key = key,
            title = "$key 프리셋",
            marketFit = "fit",
            entryRules = listOf("rule"),
            entryDsl = "close > open",
            isBuiltin = true,
        )

    private fun dailyCandle(
        code: String,
        date: LocalDate,
        open: String,
        high: String,
        low: String,
        close: String,
    ): Candle {
        // 일봉 ts = 거래일 09:00 KST (tradeDate 매칭은 KST date 기준)
        val ts =
            date
                .atTime(9, 0)
                .atZone(kst)
                .toInstant()
                .toKotlinInstant()
        return Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = ts,
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1000L,
            source = "KIS",
        )
    }

    private fun discoverResult(matches: List<ScreenerMatchDto>) =
        DaytradeDiscoverResultDto(
            presets = listOf(DaytradeDiscoverPresetDto(presetKey = "DAYTRADE_X", title = "t", entryRules = listOf("r"))),
            isBuiltin = true,
            universeSize = 100,
            evaluatedCount = 90,
            matchedCount = matches.size,
            skippedReasons = emptyMap(),
            matches = matches,
            elapsedMs = 10,
            realtimeWatchRegistered = 0,
        )

    private fun match(
        code: String,
        rank: Int,
    ) = ScreenerMatchDto(
        rank = rank,
        instrumentCode = code,
        instrumentName = "$code 종목",
        score = BigDecimal("0.5"),
        scoreComponents = emptyMap(),
        reason = "buy",
    )

    @Test
    fun `snapshot은 활성 DAYTRADE 프리셋 발굴 TOP N을 멱등 INSERT한다`() {
        val snapshotDate = LocalDate.of(2026, 6, 10)
        whenever(candidateProvider.candidates())
            .thenReturn(listOf(preset("DAYTRADE_A"), preset("NON_DAYTRADE")))
        whenever(discoverService.discover(any()))
            .thenReturn(discoverResult(listOf(match("005930", 1), match("000660", 2))))
        whenever(candleRepository.findLatestByCodeAndInterval(any(), eq(CandleInterval.ONE_DAY), eq(1)))
            .thenReturn(listOf(dailyCandle("005930", snapshotDate.minusDays(1), "70000", "71000", "69000", "70500")))
        whenever(scorecardRepository.insertIgnore(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1)

        val service =
            DiscoveryScorecardSnapshotService(
                daytradeDiscoverService = discoverService,
                candidateProvider = candidateProvider,
                scorecardRepository = scorecardRepository,
                candleRepositoryProvider = candleProvider(),
                enabled = true,
                topN = 20,
                snapshotUserId = 1,
            )

        val result = service.runSnapshot(snapshotDate)

        assertThat(result.status).isEqualTo("COMPLETED")
        assertThat(result.presets).isEqualTo(1) // DAYTRADE_A 만 (NON_DAYTRADE 제외)
        assertThat(result.candidates).isEqualTo(2)
        assertThat(result.inserted).isEqualTo(2)
        // base_close = 전일 종가 70500 으로 INSERT
        verify(scorecardRepository).insertIgnore(
            eq(snapshotDate),
            eq("DAYTRADE_A"),
            eq(1),
            eq("005930"),
            any(),
            any(),
            eq(BigDecimal("70500")),
        )
        // 발굴은 startRealtimeWatch=false 로만 호출 (관찰·기록만 — 주문/watch 0)
        verify(discoverService).discover(
            org.mockito.kotlin.argThat { presetKey == "DAYTRADE_A" && !startRealtimeWatch && interval == "1d" },
        )
    }

    @Test
    fun `scoring은 다음날 종가로 next_return_pct를 계산하고 scored_at을 마킹한다`() {
        val snapshotDate = LocalDate.of(2026, 6, 10)
        val today = snapshotDate
        val row =
            DiscoveryScorecardJpaEntity(
                snapshotDate = snapshotDate,
                presetKey = "DAYTRADE_A",
                rank = 1,
                instrumentCode = "005930",
                instrumentName = "삼성전자",
                score = BigDecimal("0.5"),
                baseClose = BigDecimal("70000"),
            )
        whenever(scorecardRepository.findByScoredAtIsNullAndSnapshotDateLessThanEqualOrderBySnapshotDateAsc(today))
            .thenReturn(listOf(row))
        // snapshotDate 거래일 일봉: 종가 71400 → (71400/70000-1)*100 = 2.0%
        whenever(candleRepository.findLatestByCodeAndInterval(eq("005930"), eq(CandleInterval.ONE_DAY), any()))
            .thenReturn(
                listOf(
                    dailyCandle("005930", snapshotDate, "70700", "72000", "70100", "71400"),
                ),
            )

        val service =
            DiscoveryScorecardScoringService(
                scorecardRepository = scorecardRepository,
                candleRepositoryProvider = candleProvider(),
                enabled = true,
            )

        val result = service.runScoring(today)

        assertThat(result.status).isEqualTo("COMPLETED")
        assertThat(result.scored).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(row.nextClose).isEqualByComparingTo("71400")
        assertThat(row.nextReturnPct).isEqualByComparingTo("2.0000")
        assertThat(row.nextOpenReturnPct).isEqualByComparingTo("1.0000") // (70700/70000-1)*100
        assertThat(row.scoredAt).isNotNull()
        verify(scorecardRepository).saveAll(any<List<DiscoveryScorecardJpaEntity>>())
    }

    @Test
    fun `scoring은 대상 일봉 미적재 시 미채점 유지하고 saveAll을 호출하지 않는다`() {
        val snapshotDate = LocalDate.of(2026, 6, 10)
        val today = snapshotDate
        val row =
            DiscoveryScorecardJpaEntity(
                snapshotDate = snapshotDate,
                presetKey = "DAYTRADE_A",
                rank = 1,
                instrumentCode = "005930",
                instrumentName = "삼성전자",
                score = BigDecimal("0.5"),
                baseClose = BigDecimal("70000"),
            )
        whenever(scorecardRepository.findByScoredAtIsNullAndSnapshotDateLessThanEqualOrderBySnapshotDateAsc(today))
            .thenReturn(listOf(row))
        // 다음날 일봉 미적재 — snapshotDate 거래일 candle 없음 (전일만 존재)
        whenever(candleRepository.findLatestByCodeAndInterval(eq("005930"), eq(CandleInterval.ONE_DAY), any()))
            .thenReturn(
                listOf(dailyCandle("005930", snapshotDate.minusDays(1), "70000", "70000", "70000", "70000")),
            )

        val service =
            DiscoveryScorecardScoringService(
                scorecardRepository = scorecardRepository,
                candleRepositoryProvider = candleProvider(),
                enabled = true,
            )

        val result = service.runScoring(today)

        assertThat(result.scored).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(1)
        assertThat(row.scoredAt).isNull()
        verify(scorecardRepository, never()).saveAll(any<List<DiscoveryScorecardJpaEntity>>())
    }
}
