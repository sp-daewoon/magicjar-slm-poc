package kr.spdaewoon.magicjar.market.calculator

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.trend.CrossSignal
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.SectorRankEntry
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot
import kr.spdaewoon.magicjar.domain.trend.TrendLabel
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 1차 SMA(Simple Moving Average) 기반 추세 계산 구현 (ADR 0100 §2-2 + §2-2-D 표준 정의).
 *
 * **Pure math** — Spring `@Component` 이지만 외부 의존 0 (DB·HTTP·캐시 모두 0).
 *
 * ## 알고리즘 LOCK
 * - **MA**: SMA(period) = sum(closeAdj[t-period+1 .. t]) / period · `closeAdj` null fallback `closeRaw`
 * - **CrossSignal**: shortMa · longMa 직전 ↔ 현재 봉 cross
 * - **TrendLabel**: longMa[t] 5일 전 대비 변화율 ±0.5%
 * - **dataInsufficient**: candles.size < longMa
 * - **SectorRanking todayTop**: 등락률 0.7 + 거래대금 정규화 0.3
 * - **SectorRanking longTop**: Mansfield RS + 우상향 fit 가산점 (+0.1 / period)
 *
 * 자체 추론 0 — 본 알고리즘은 ADR 0100 §2-2-D 표준 정의 1:1 정합.
 */
@Component
class SmaTrendCalculator : TrendCalculator {
    /**
     * SMA 계산 — period 미만 candle 시 null.
     */
    internal fun sma(
        closes: List<BigDecimal>,
        period: Int,
    ): BigDecimal? {
        if (closes.size < period || period < 1) return null
        val window = closes.subList(closes.size - period, closes.size)
        val sum = window.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        return sum.divide(BigDecimal(period), MC)
    }

    /**
     * candle 의 종가 추출 — closeAdj null fallback closeRaw (ADR 0019 정합).
     */
    private fun Candle.close(): BigDecimal = closeAdj ?: closeRaw

    override fun computeIndexTrend(
        code: String,
        candles: List<Candle>,
        periods: List<Int>,
        crossPair: Pair<Int, Int>?,
        isUserCustom: Boolean,
    ): IndexTrend {
        require(periods.isNotEmpty()) { "periods 1개 이상 필수" }
        require(periods.all { it in 1..252 }) { "각 period 1~252 사이 (입력=$periods)" }
        require(periods == periods.sorted().distinct()) { "periods sorted ASC + 중복 없음 (입력=$periods)" }

        val closes = candles.map { it.close() }
        val longMa = crossPair?.second ?: periods.last()
        val shortMa = crossPair?.first ?: periods.first()
        val dataInsufficient = closes.size < longMa

        // ma map — 각 period 별 SMA (없으면 entry 0)
        val maMap: Map<Int, BigDecimal> =
            periods
                .mapNotNull { p -> sma(closes, p)?.let { p to it.setScale(4, RoundingMode.HALF_UP) } }
                .toMap()

        // cross signal — 직전 봉 vs 현재 봉
        val crossSignal = computeCrossSignal(closes, shortMa, longMa)

        // trend label — longMa 5일 전 변화율
        val trendLabel = computeTrendLabel(closes, longMa)

        val lastClose = closes.lastOrNull()
        val changeRate =
            if (closes.size >= 2) {
                val prev = closes[closes.size - 2]
                if (prev.signum() == 0) {
                    null
                } else {
                    (closes.last() - prev)
                        .divide(prev, MC)
                        .multiply(BigDecimal(100))
                        .setScale(4, RoundingMode.HALF_UP)
                }
            } else {
                null
            }

        return IndexTrend(
            code = code,
            ma = maMap,
            crossSignal = crossSignal,
            trendLabel = trendLabel,
            periodsUsed = periods,
            isUserCustom = isUserCustom,
            dataInsufficient = dataInsufficient,
            lastClose = lastClose?.setScale(4, RoundingMode.HALF_UP),
            changeRate = changeRate,
        )
    }

    /** ADR 0100 §2-2-D CrossSignal 표준 정의 — 직전 봉 ↔ 현재 봉 SMA cross. */
    internal fun computeCrossSignal(
        closes: List<BigDecimal>,
        short: Int,
        long: Int,
    ): CrossSignal {
        if (closes.size < long + 1) return CrossSignal.NEUTRAL
        val prevCloses = closes.subList(0, closes.size - 1)
        val shortPrev = sma(prevCloses, short) ?: return CrossSignal.NEUTRAL
        val longPrev = sma(prevCloses, long) ?: return CrossSignal.NEUTRAL
        val shortNow = sma(closes, short) ?: return CrossSignal.NEUTRAL
        val longNow = sma(closes, long) ?: return CrossSignal.NEUTRAL
        return when {
            shortPrev <= longPrev && shortNow > longNow -> CrossSignal.GOLDEN
            shortPrev >= longPrev && shortNow < longNow -> CrossSignal.DEAD
            else -> CrossSignal.NEUTRAL
        }
    }

    /** ADR 0100 §2-2-D TrendLabel 표준 정의 — longMa[t] 5일 전 변화율 ±0.5%. */
    internal fun computeTrendLabel(
        closes: List<BigDecimal>,
        long: Int,
    ): TrendLabel {
        if (closes.size < long + 5) return TrendLabel.SIDEWAYS
        val longNow = sma(closes, long) ?: return TrendLabel.SIDEWAYS
        val pastWindow = closes.subList(0, closes.size - 5)
        val longPast = sma(pastWindow, long) ?: return TrendLabel.SIDEWAYS
        if (longPast.signum() == 0) return TrendLabel.SIDEWAYS
        val close = closes.last()
        val changeRatio = (longNow - longPast).divide(longPast, MC)
        return when {
            close > longNow && changeRatio >= TREND_LABEL_THRESHOLD -> TrendLabel.UP
            close < longNow && changeRatio <= TREND_LABEL_THRESHOLD.negate() -> TrendLabel.DOWN
            else -> TrendLabel.SIDEWAYS
        }
    }

    override fun computeSectorRanking(
        snapshots: List<SectorSnapshot>,
        baselineSnapshots: List<SectorSnapshot>?,
        sectorDailyCandles: Map<String, List<Candle>>,
        baselineCandles: List<Candle>,
        periods: List<Int>,
        rsPeriod: Int,
        isUserCustom: Boolean,
        topN: Int,
    ): SectorRanking {
        require(periods.isNotEmpty()) { "periods 1개 이상 필수" }
        require(rsPeriod in 1..252) { "rsPeriod 1~252 사이 (입력=$rsPeriod)" }
        require(topN >= 1) { "topN 1 이상 필수 (입력=$topN)" }

        // baseline 거래대금 정규화 map (전일 대비)
        val baselineTradeValueByCode: Map<String, BigDecimal> =
            baselineSnapshots
                ?.mapNotNull { snap ->
                    val tv = snap.tradeValue
                    if (tv != null && tv.signum() > 0) snap.sectorCode to tv else null
                }?.toMap()
                ?: emptyMap()

        val todayTopEntries = computeTodayTop(snapshots, baselineTradeValueByCode, topN)
        val longTopEntries =
            computeLongTop(
                snapshots = snapshots,
                sectorDailyCandles = sectorDailyCandles,
                baselineCandles = baselineCandles,
                periods = periods,
                rsPeriod = rsPeriod,
                topN = topN,
            )

        return SectorRanking(
            todayTop = todayTopEntries,
            longTop = longTopEntries,
            periodsUsed = periods,
            rsPeriod = rsPeriod,
            isUserCustom = isUserCustom,
        )
    }

    /** todayTop — 등락률 0.7 + 거래대금 정규화 0.3 가중 (ADR 0100 §2-2-D). */
    private fun computeTodayTop(
        snapshots: List<SectorSnapshot>,
        baselineTradeValueByCode: Map<String, BigDecimal>,
        topN: Int,
    ): List<SectorRankEntry> {
        if (snapshots.isEmpty()) return emptyList()
        return snapshots
            .map { snap ->
                val changeWeight = snap.changeRate.multiply(WEIGHT_CHANGE)
                val tradeNow = snap.tradeValue
                val tradeWeight =
                    if (tradeNow != null && tradeNow.signum() > 0) {
                        val baseline = baselineTradeValueByCode[snap.sectorCode]
                        if (baseline != null && baseline.signum() > 0) {
                            // 정규화: 전일 대비 비율 * 100 → % 환산 → 가중치 0.3
                            tradeNow
                                .divide(baseline, MC)
                                .multiply(BigDecimal(100))
                                .multiply(WEIGHT_TRADE)
                        } else {
                            BigDecimal.ZERO
                        }
                    } else {
                        BigDecimal.ZERO
                    }
                val score = (changeWeight + tradeWeight).setScale(4, RoundingMode.HALF_UP)
                SectorRankEntry(
                    sectorCode = snap.sectorCode,
                    sectorName = snap.sectorName,
                    changeRate = snap.changeRate,
                    tradeValue = snap.tradeValue,
                    score = score,
                    metric = "등락률 ${snap.changeRate}% · 거래대금 ${snap.tradeValue ?: "N/A"}",
                )
            }.sortedByDescending { it.score }
            .take(topN)
    }

    /** longTop — Mansfield RS rsPeriod 기준 + maPeriods 우상향 fit 가산점. */
    private fun computeLongTop(
        snapshots: List<SectorSnapshot>,
        sectorDailyCandles: Map<String, List<Candle>>,
        baselineCandles: List<Candle>,
        periods: List<Int>,
        rsPeriod: Int,
        topN: Int,
    ): List<SectorRankEntry> {
        if (snapshots.isEmpty() || baselineCandles.size < rsPeriod + 1) return emptyList()

        val baselineCloses = baselineCandles.map { it.close() }
        val baselineNow = baselineCloses.last()
        val baselinePast = baselineCloses[baselineCloses.size - 1 - rsPeriod]
        if (baselinePast.signum() == 0) return emptyList()
        val baselineRatio = baselineNow.divide(baselinePast, MC)
        if (baselineRatio.signum() == 0) return emptyList()

        return snapshots
            .mapNotNull { snap ->
                val candles = sectorDailyCandles[snap.sectorCode] ?: return@mapNotNull null
                if (candles.size < rsPeriod + 1) return@mapNotNull null
                val closes = candles.map { it.close() }
                val now = closes.last()
                val past = closes[closes.size - 1 - rsPeriod]
                if (past.signum() == 0) return@mapNotNull null
                val sectorRatio = now.divide(past, MC)
                val rs = sectorRatio.divide(baselineRatio, MC).setScale(4, RoundingMode.HALF_UP)

                // 우상향 fit — 각 period MA 가 5일 전보다 큰 경우 +0.1 가산
                val fitCount = periods.count { p -> isUptrendFit(closes, p) }
                val fitBonus = BigDecimal(fitCount).multiply(UPTREND_FIT_BONUS)
                val score = (rs + fitBonus).setScale(4, RoundingMode.HALF_UP)

                SectorRankEntry(
                    sectorCode = snap.sectorCode,
                    sectorName = snap.sectorName,
                    changeRate = snap.changeRate,
                    tradeValue = snap.tradeValue,
                    score = score,
                    metric = "RS=$rs · 우상향 fit=$fitCount/${periods.size}",
                )
            }.sortedByDescending { it.score }
            .take(topN)
    }

    /** MA[t] > MA[t-5] 면 우상향 fit. */
    private fun isUptrendFit(
        closes: List<BigDecimal>,
        period: Int,
    ): Boolean {
        if (closes.size < period + 5) return false
        val now = sma(closes, period) ?: return false
        val past = sma(closes.subList(0, closes.size - 5), period) ?: return false
        return now > past
    }

    companion object {
        private val MC = MathContext(16, RoundingMode.HALF_UP)
        private val TREND_LABEL_THRESHOLD = BigDecimal("0.005")
        private val WEIGHT_CHANGE = BigDecimal("0.7")
        private val WEIGHT_TRADE = BigDecimal("0.3")
        private val UPTREND_FIT_BONUS = BigDecimal("0.1")
    }
}
