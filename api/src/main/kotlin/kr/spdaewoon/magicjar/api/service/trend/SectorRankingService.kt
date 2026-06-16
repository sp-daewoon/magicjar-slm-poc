package kr.spdaewoon.magicjar.api.service.trend

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshotReader
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot
import kr.spdaewoon.magicjar.market.calculator.TrendCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 섹터 ranking query 단일 진입점 (ADR 0100 §2-5 `MarketSectorController` reuse).
 *
 * - `snapshot()` — 섹터 list 최신 (read-only · ranking 미적용)
 * - `ranking(userId, ...)` — todayTop 5 + longTop 5 (Mansfield RS rsPeriod 기준)
 *
 * R1 market-data 가 `MarketIndexSnapshotReader` real adapter 발행 전까지는 Noop adapter 가 empty list 반환 →
 * frontend 는 "데이터 없음" 한국어 표시 (mock 합성 0 — feedback_no_mock_data 정합).
 */
@Service
class SectorRankingService(
    private val candleRepository: CandleRepository,
    private val trendCalculator: TrendCalculator,
    private val trendPreferenceService: TrendPreferenceService,
    private val marketIndexSnapshotReader: MarketIndexSnapshotReader,
) {
    private val log = LoggerFactory.getLogger(SectorRankingService::class.java)

    /**
     * 섹터 list 최신 snapshot (ranking 미적용 · 단순 read).
     */
    @Transactional(readOnly = true)
    fun snapshot(): List<SectorSnapshot> = marketIndexSnapshotReader.findLatestSectorSnapshots()

    /**
     * 섹터 ranking (todayTop 5 + longTop 5).
     *
     * @param userId 사용자 id (TrendPreference 적용)
     * @param periodsOverride 일회성 override
     * @param rsPeriodOverride 일회성 override
     */
    @Transactional(readOnly = true)
    fun ranking(
        userId: String,
        periodsOverride: List<Int>? = null,
        rsPeriodOverride: Int? = null,
        baselineCode: String = BASELINE_CODE_DEFAULT,
    ): SectorRanking {
        val pref = trendPreferenceService.getOrDefault(userId)
        val periods = periodsOverride ?: pref.maPeriods
        val rsPeriod = rsPeriodOverride ?: pref.rsPeriod
        val isUserCustom =
            periodsOverride != null || rsPeriodOverride != null || !pref.isDefault()

        val snapshots = marketIndexSnapshotReader.findLatestSectorSnapshots()
        val baselineSnapshots = marketIndexSnapshotReader.findPreviousSectorSnapshots()
        val sectorCandles: Map<String, List<Candle>> = loadSectorCandles(snapshots.map { it.sectorCode })
        val baselineCandles: List<Candle> =
            candleRepository.findByCodeAndInterval(baselineCode, CandleInterval.ONE_DAY)
        log.debug(
            "sector ranking input: snapshots={} sectorCandles={} baselineCandles={}",
            snapshots.size,
            sectorCandles.size,
            baselineCandles.size,
        )

        return trendCalculator.computeSectorRanking(
            snapshots = snapshots,
            baselineSnapshots = baselineSnapshots,
            sectorDailyCandles = sectorCandles,
            baselineCandles = baselineCandles,
            periods = periods,
            rsPeriod = rsPeriod,
            isUserCustom = isUserCustom,
        )
    }

    private fun loadSectorCandles(sectorCodes: List<String>): Map<String, List<Candle>> =
        sectorCodes.associateWith { code ->
            candleRepository.findByCodeAndInterval(code, CandleInterval.ONE_DAY)
        }

    companion object {
        /** KOSPI default baseline (Mansfield RS 기준 — ADR 0100 §2-2-D). */
        const val BASELINE_CODE_DEFAULT: String = "0001"
    }
}
