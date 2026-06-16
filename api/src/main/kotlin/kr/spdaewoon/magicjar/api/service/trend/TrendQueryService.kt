package kr.spdaewoon.magicjar.api.service.trend

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshotReader
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceDefaults
import kr.spdaewoon.magicjar.market.calculator.TrendCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode

/**
 * 지수 추세 query 단일 진입점 (ADR 0100 §2-5 `MarketIndexController` reuse).
 *
 * - `snapshot()` — 4 지수 (KOSPI · KOSDAQ · NASDAQ · S&P) 카드 query (user pref 적용 trend 라벨)
 * - `trend(code, overrides)` — 단일 지수 상세 (periods override 우선 · 없으면 user pref)
 *
 * 시장지수는 `market_index_snapshot` 이 단일 진실원이다.
 * 1분 snapshot 에서 KST 날짜별 최신 1건을 일봉처럼 조립하고, 구형 데이터가 없을 때만 candle table 로 fallback 한다.
 * 데이터 0건 시 `dataInsufficient=true` flag echo (mock 합성 0 — feedback_no_mock_data 정합).
 */
@Service
class TrendQueryService(
    private val candleRepository: CandleRepository,
    private val trendCalculator: TrendCalculator,
    private val trendPreferenceService: TrendPreferenceService,
    private val marketIndexSnapshotReader: MarketIndexSnapshotReader,
) {
    private val log = LoggerFactory.getLogger(TrendQueryService::class.java)

    /**
     * 4 지수 카드 query.
     *
     * @param userId 사용자 id (TrendPreference 적용)
     * @param indexCodes default [INDEX_CODES_DEFAULT] (KOSPI · KOSDAQ · NASDAQ · S&P)
     */
    @Transactional(readOnly = true)
    fun snapshot(
        userId: String,
        indexCodes: List<String> = INDEX_CODES_DEFAULT,
    ): List<IndexTrend> {
        val pref = trendPreferenceService.getOrDefault(userId)
        val isUserCustom = !pref.isDefault()
        return indexCodes.map { code ->
            computeTrend(
                code = code,
                periods = pref.maPeriods,
                crossPair = pref.crossPair,
                isUserCustom = isUserCustom,
            )
        }
    }

    /**
     * 단일 지수 추세 상세.
     *
     * @param userId 사용자 id
     * @param code KIS index code
     * @param periodsOverride 일회성 override (URL share / 테스트) · null 시 user pref
     */
    @Transactional(readOnly = true)
    fun trend(
        userId: String,
        code: String,
        periodsOverride: List<Int>? = null,
    ): IndexTrend {
        val pref = trendPreferenceService.getOrDefault(userId)
        val periods = periodsOverride ?: pref.maPeriods
        // override 사용 시에도 사용자 본인 pref 와 다르면 isUserCustom 로 표기 (frontend "임시 적용" 표시 가능)
        val isUserCustom =
            periodsOverride != null &&
                periodsOverride != TrendPreferenceDefaults.MA_PERIODS ||
                periodsOverride == null &&
                !pref.isDefault()
        val crossPair = if (periodsOverride != null) null else pref.crossPair
        return computeTrend(code = code, periods = periods, crossPair = crossPair, isUserCustom = isUserCustom)
    }

    @Transactional(readOnly = true)
    fun candles(
        code: String,
        interval: String,
        limit: Int,
    ): List<IndexCandle> {
        val effectiveLimit = limit.coerceIn(1, INDEX_CANDLE_MAX_LIMIT)
        val snapshots =
            when (interval) {
                "1d" -> marketIndexSnapshotReader.findDailyIndexSeries(code, effectiveLimit)
                else -> marketIndexSnapshotReader.findIndexSnapshotSeries(code, effectiveLimit)
            }
        return snapshots.toIndexCandles()
    }

    private fun computeTrend(
        code: String,
        periods: List<Int>,
        crossPair: Pair<Int, Int>?,
        isUserCustom: Boolean,
    ): IndexTrend {
        val latestSnapshot = marketIndexSnapshotReader.findLatestIndex(code)
        val candles = loadIndexCandles(code, periods, crossPair)
        log.debug("trend index series loaded: code={} count={} latestSnapshot={}", code, candles.size, latestSnapshot != null)
        val computed =
            trendCalculator.computeIndexTrend(
                code = code,
                candles = candles,
                periods = periods,
                crossPair = crossPair,
                isUserCustom = isUserCustom,
            )
        return latestSnapshot?.let {
            computed.copy(
                lastClose = it.price.setScale(4, RoundingMode.HALF_UP),
                changeRate = it.changeRate ?: computed.changeRate,
            )
        } ?: computed
    }

    private fun loadIndexCandles(
        code: String,
        periods: List<Int>,
        crossPair: Pair<Int, Int>?,
    ): List<Candle> {
        val longMa = maxOf(periods.maxOrNull() ?: 0, crossPair?.second ?: 0)
        val historyLimit = (longMa + TREND_LOOKBACK_BUFFER_DAYS).coerceAtLeast(2)
        val snapshotCandles =
            marketIndexSnapshotReader
                .findDailyIndexSeries(code, historyLimit)
                .map { it.toCandle() }
        if (snapshotCandles.isNotEmpty()) return snapshotCandles
        return candleRepository.findByCodeAndInterval(code, CandleInterval.ONE_DAY)
    }

    private fun MarketIndexSnapshot.toCandle(): Candle =
        Candle(
            code = code,
            interval = CandleInterval.ONE_DAY,
            ts = ts,
            openRaw = price,
            highRaw = price,
            lowRaw = price,
            closeRaw = price,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = price,
            volume = 0L,
            source = MARKET_INDEX_SNAPSHOT_SOURCE,
        )

    private fun List<MarketIndexSnapshot>.toIndexCandles(): List<IndexCandle> =
        mapIndexed { index, snapshot ->
            val open = getOrNull(index - 1)?.price ?: snapshot.price
            val close = snapshot.price
            IndexCandle(
                ts = snapshot.ts,
                open = open,
                high = maxOf(open, close),
                low = minOf(open, close),
                close = close,
            )
        }

    companion object {
        private const val TREND_LOOKBACK_BUFFER_DAYS: Int = 6
        private const val INDEX_CANDLE_MAX_LIMIT: Int = 1_000
        private const val MARKET_INDEX_SNAPSHOT_SOURCE: String = "MARKET_INDEX_SNAPSHOT"

        /**
         * 4 지수 default codes (ADR 0100 §2-5).
         * - 0001 = KOSPI · 1001 = KOSDAQ · .IXIC = NASDAQ · .SPX = S&P 500
         */
        val INDEX_CODES_DEFAULT: List<String> = listOf("0001", "1001", ".IXIC", ".SPX")
    }
}
