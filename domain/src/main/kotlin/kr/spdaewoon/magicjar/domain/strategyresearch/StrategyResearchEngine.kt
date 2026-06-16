package kr.spdaewoon.magicjar.domain.strategyresearch

import kotlinx.datetime.Instant
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 전략 후보 리서치 엔진.
 *
 * ### research-candidates-db 전환 (V74)
 * 후보 진입 조건은 더 이상 Kotlin 람다로 하드코딩하지 않는다.
 * - 후보 메타(key·title·entryDsl 등)는 [ResearchCandidateProvider]로 공급(기본 [BuiltinResearchCandidates] = V74 시드 정합).
 * - 진입 시그널은 [CandidateEntryEvaluator]로 `entryDsl`을 평가(api 모듈이 strategy-engine 어댑터 주입).
 *
 * @param entryEvaluator 진입 DSL 평가기. 기본값은 항상 미진입(false)을 내는 no-op — domain 단독 테스트/안전망.
 * @param candidateProvider 후보 정의 공급자. 기본값은 코드 권위 [BuiltinResearchCandidates].
 */
class StrategyResearchEngine(
    private val entryEvaluator: CandidateEntryEvaluator = CandidateEntryEvaluator { _, _, _ -> false },
    private val candidateProvider: ResearchCandidateProvider = BuiltinResearchCandidates,
) {
    fun research(
        request: StrategyResearchRequest,
        candles: List<ResearchCandle>,
    ): StrategyResearchReport {
        val sortedCandles =
            candles
                .filter { it.code == request.code && it.interval == request.interval }
                .sortedBy { it.ts }

        val warnings = dataWarnings(sortedCandles)
        if (sortedCandles.size < MIN_CANDLES) {
            return StrategyResearchReport(
                request = request,
                candleCount = sortedCandles.size,
                marketRegime = marketRegime(sortedCandles),
                warnings = warnings + "전략 후보를 평가하려면 최소 ${MIN_CANDLES}개 candle이 필요합니다.",
                candles = sortedCandles.map(::toPoint),
                candidates = emptyList(),
            )
        }

        val candidates =
            candidateProvider
                .candidates()
                .map { definition -> definition.toCandidate(request, sortedCandles) }
                .sortedByDescending { it.score }

        return StrategyResearchReport(
            request = request,
            candleCount = sortedCandles.size,
            marketRegime = marketRegime(sortedCandles),
            warnings = warnings,
            candles = sortedCandles.map(::toPoint),
            candidates = candidates,
        )
    }

    private fun ResearchCandidateDefinition.toCandidate(
        request: StrategyResearchRequest,
        candles: List<ResearchCandle>,
    ): StrategyResearchCandidate {
        // 진입 시그널 index 집합은 exit profile과 무관하므로 한 번만 평가(평가기 호출 절약).
        val signalIndices = entrySignalIndices(candles, entryDsl)
        val variants =
            exitProfiles(request)
                .map { profile ->
                    val profileRequest =
                        request.copy(
                            targetProfitPct = profile.targetProfitPct,
                            stopLossPct = profile.stopLossPct,
                            maxHoldBars = profile.maxHoldBars,
                        )
                    val trades = simulate(profileRequest, candles, signalIndices)
                    val stats = stats(trades)
                    CandidateVariant(profileRequest, trades, stats, score(stats))
                }
        val bestVariant = variants.maxWith(compareBy<CandidateVariant> { it.score }.thenBy { it.stats.trades })
        return StrategyResearchCandidate(
            key = key,
            title = title,
            summary = summary(bestVariant.stats),
            marketFit = marketFit,
            entryRules = entryRules,
            exitRules = exitRules(bestVariant.request),
            riskRules = riskRules(),
            entryDslDraft = entryDsl,
            exitDslDraft = exitDsl(bestVariant.request),
            stats = bestVariant.stats,
            trades = bestVariant.trades,
            score = bestVariant.score,
        )
    }

    /**
     * `entryDsl`이 진입 시그널을 내는 candle index 집합 ([CandidateEntryEvaluator] 위임).
     *
     * index 0은 직전 candle이 없어 평가에서 제외(기존 람다 `index = 1` 시작 정합).
     */
    private fun entrySignalIndices(
        candles: List<ResearchCandle>,
        entryDsl: String,
    ): Set<Int> {
        val signals = mutableSetOf<Int>()
        for (index in 1 until candles.lastIndex) {
            if (entryEvaluator.evaluateEntry(entryDsl, candles, index)) {
                signals += index
            }
        }
        return signals
    }

    private fun simulate(
        request: StrategyResearchRequest,
        candles: List<ResearchCandle>,
        signalIndices: Set<Int>,
    ): List<StrategyResearchTrade> {
        val trades = mutableListOf<StrategyResearchTrade>()
        var index = 1
        while (index < candles.lastIndex) {
            if (index !in signalIndices) {
                index += 1
                continue
            }

            val signalIndex = index
            val entryIndex = index + 1
            if (entryIndex > candles.lastIndex) break

            val entryPrice = candles[entryIndex].open.toDouble()
            if (entryPrice <= 0.0) {
                index += 1
                continue
            }

            val targetPrice = entryPrice * (1.0 + request.targetProfitPct / 100.0)
            val stopPrice = entryPrice * (1.0 - request.stopLossPct / 100.0)
            val maxExitIndex = min(candles.lastIndex, entryIndex + request.maxHoldBars)
            var exitIndex = maxExitIndex
            var exitPrice = candles[maxExitIndex].close.toDouble()
            var exitReason = StrategyResearchExitReason.TIME_EXIT

            for (cursor in entryIndex..maxExitIndex) {
                val candle = candles[cursor]
                val low = candle.low.toDouble()
                val high = candle.high.toDouble()
                if (low <= stopPrice) {
                    exitIndex = cursor
                    exitPrice = stopPrice
                    exitReason = StrategyResearchExitReason.STOP_LOSS
                    break
                }
                if (high >= targetPrice) {
                    exitIndex = cursor
                    exitPrice = targetPrice
                    exitReason = StrategyResearchExitReason.TAKE_PROFIT
                    break
                }
            }

            val returnPct = ((exitPrice - entryPrice) / entryPrice) * 100.0
            trades +=
                StrategyResearchTrade(
                    signalTime = candles[signalIndex].ts,
                    entryTime = candles[entryIndex].ts,
                    exitTime = candles[exitIndex].ts,
                    entryPrice = bd(entryPrice),
                    exitPrice = bd(exitPrice),
                    returnPct = round(returnPct),
                    exitReason = exitReason,
                    barsHeld = max(1, exitIndex - entryIndex + 1),
                    reason = "진입 조건 충족 (${candles[signalIndex].ts}) 후 다음 candle 시가 체결",
                )
            index = exitIndex + 1
        }
        return trades
    }

    private fun stats(trades: List<StrategyResearchTrade>): StrategyResearchStats {
        if (trades.isEmpty()) {
            return StrategyResearchStats(
                trades = 0,
                wins = 0,
                losses = 0,
                winRatePct = 0.0,
                totalReturnPct = 0.0,
                avgTradeReturnPct = 0.0,
                bestTradePct = 0.0,
                worstTradePct = 0.0,
                maxDrawdownPct = 0.0,
                maxConsecutiveLosses = 0,
                profitFactor = null,
            )
        }

        val wins = trades.count { it.returnPct > 0.0 }
        val losses = trades.count { it.returnPct < 0.0 }
        val winRate = wins.toDouble() / trades.size.toDouble() * 100.0
        val gains = trades.filter { it.returnPct > 0.0 }.sumOf { it.returnPct }
        val lossAbs = abs(trades.filter { it.returnPct < 0.0 }.sumOf { it.returnPct })
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        var consecutiveLosses = 0
        var maxConsecutiveLosses = 0

        trades.forEach { trade ->
            equity *= 1.0 + trade.returnPct / 100.0
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak * 100.0)
            if (trade.returnPct < 0.0) {
                consecutiveLosses += 1
                maxConsecutiveLosses = max(maxConsecutiveLosses, consecutiveLosses)
            } else {
                consecutiveLosses = 0
            }
        }

        return StrategyResearchStats(
            trades = trades.size,
            wins = wins,
            losses = losses,
            winRatePct = round(winRate),
            totalReturnPct = round((equity - 1.0) * 100.0),
            avgTradeReturnPct = round(trades.sumOf { it.returnPct } / trades.size.toDouble()),
            bestTradePct = round(trades.maxOf { it.returnPct }),
            worstTradePct = round(trades.minOf { it.returnPct }),
            maxDrawdownPct = round(maxDrawdown),
            maxConsecutiveLosses = maxConsecutiveLosses,
            profitFactor = if (lossAbs == 0.0) null else round(gains / lossAbs),
        )
    }

    private fun exitRules(request: StrategyResearchRequest): List<String> =
        listOf(
            "목표 수익 ${request.targetProfitPct}% 도달 시 익절",
            "손실 ${request.stopLossPct}% 도달 시 손절",
            "${request.maxHoldBars}개 candle 안에 목표 미도달 시 시간 청산",
        )

    private fun riskRules(): List<String> =
        listOf(
            "동시에 1포지션만 보유",
            "신호 candle 종가가 아니라 다음 candle 시가 체결로 평가",
            "같은 candle에서 익절·손절이 모두 닿으면 손절을 우선 적용",
        )

    private fun exitDsl(request: StrategyResearchRequest): String =
        "take_profit(${request.targetProfitPct}%) or stop_loss(${request.stopLossPct}%) or ${request.maxHoldBars} bars timeout"

    private fun marketRegime(candles: List<ResearchCandle>): StrategyResearchMarketRegime {
        if (candles.size < 2) {
            return StrategyResearchMarketRegime(
                type = "UNKNOWN",
                trendPct = 0.0,
                volatilityPct = 0.0,
                explanation = "candle 수가 부족합니다.",
            )
        }
        val first = candles.first().close.toDouble()
        val last = candles.last().close.toDouble()
        val trendPct = if (first == 0.0) 0.0 else ((last - first) / first) * 100.0
        val returns =
            candles
                .zipWithNext()
                .mapNotNull { (prev, cur) ->
                    val previous = prev.close.toDouble()
                    if (previous == 0.0) null else abs((cur.close.toDouble() - previous) / previous * 100.0)
                }
        val volatility = if (returns.isEmpty()) 0.0 else returns.average()
        val type =
            when {
                trendPct <= -6.0 -> "DOWNTREND"
                trendPct >= 6.0 -> "UPTREND"
                else -> "SIDEWAYS"
            }
        val explanation =
            when (type) {
                "DOWNTREND" -> "구간 수익률이 음수라 추세 추종보다 짧은 반등형 후보를 우선 평가했습니다."
                "UPTREND" -> "구간 수익률이 양수라 눌림 후 회복 후보가 유리할 수 있습니다."
                else -> "방향성이 약해 평균 회귀와 거래량 반등 후보를 함께 평가했습니다."
            }
        return StrategyResearchMarketRegime(
            type = type,
            trendPct = round(trendPct),
            volatilityPct = round(volatility),
            explanation = explanation,
        )
    }

    private fun dataWarnings(candles: List<ResearchCandle>): List<String> {
        val warnings = mutableListOf<String>()
        if (candles.isEmpty()) {
            warnings += "해당 종목·interval·기간에 저장된 candle이 없습니다."
            return warnings
        }
        val duplicated = candles.size - candles.map { it.ts }.distinct().size
        if (duplicated > 0) warnings += "중복 candle timestamp ${duplicated}건이 감지되었습니다."
        return warnings
    }

    private fun score(stats: StrategyResearchStats): Double {
        if (stats.trades == 0) return -100.0
        val tradePenalty = if (stats.trades < 3) (3 - stats.trades) * 2.0 else 0.0
        return round(stats.totalReturnPct + stats.winRatePct * 0.08 - stats.maxDrawdownPct * 1.3 - tradePenalty)
    }

    private fun summary(stats: StrategyResearchStats): String =
        if (stats.trades == 0) {
            "해당 구간에서 조건을 만족한 진입이 없었습니다."
        } else {
            "거래 ${stats.trades}회, 승률 ${stats.winRatePct}%, 누적 ${stats.totalReturnPct}%"
        }

    private fun toPoint(candle: ResearchCandle): StrategyResearchCandlePoint =
        StrategyResearchCandlePoint(
            ts = candle.ts,
            open = candle.open,
            high = candle.high,
            low = candle.low,
            close = candle.close,
            volume = candle.volume,
        )

    private fun exitProfiles(request: StrategyResearchRequest): List<ExitProfile> =
        listOf(
            ExitProfile(request.targetProfitPct, request.stopLossPct, request.maxHoldBars),
            ExitProfile(0.5, 0.3, 4),
            ExitProfile(0.8, 0.5, 8),
            ExitProfile(1.0, 0.5, 12),
            ExitProfile(1.2, 0.8, 12),
            ExitProfile(1.5, 0.8, 20),
            ExitProfile(2.0, 1.0, 30),
        ).distinctBy { "${it.targetProfitPct}:${it.stopLossPct}:${it.maxHoldBars}" }

    private data class CandidateVariant(
        val request: StrategyResearchRequest,
        val trades: List<StrategyResearchTrade>,
        val stats: StrategyResearchStats,
        val score: Double,
    )

    private data class ExitProfile(
        val targetProfitPct: Double,
        val stopLossPct: Double,
        val maxHoldBars: Int,
    )

    private companion object {
        const val MIN_CANDLES = 40
    }
}

data class StrategyResearchRequest(
    val userId: String,
    val code: String,
    val interval: String,
    val from: Instant,
    val to: Instant,
    val targetProfitPct: Double = 1.5,
    val stopLossPct: Double = 0.8,
    val maxHoldBars: Int = 12,
)

data class ResearchCandle(
    val code: String,
    val interval: String,
    val ts: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class StrategyResearchReport(
    val request: StrategyResearchRequest,
    val candleCount: Int,
    val marketRegime: StrategyResearchMarketRegime,
    val warnings: List<String>,
    val candles: List<StrategyResearchCandlePoint>,
    val candidates: List<StrategyResearchCandidate>,
)

data class StrategyResearchMarketRegime(
    val type: String,
    val trendPct: Double,
    val volatilityPct: Double,
    val explanation: String,
)

data class StrategyResearchCandlePoint(
    val ts: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
)

data class StrategyResearchCandidate(
    val key: String,
    val title: String,
    val summary: String,
    val marketFit: String,
    val entryRules: List<String>,
    val exitRules: List<String>,
    val riskRules: List<String>,
    val entryDslDraft: String,
    val exitDslDraft: String,
    val stats: StrategyResearchStats,
    val trades: List<StrategyResearchTrade>,
    val score: Double,
)

data class StrategyResearchStats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val totalReturnPct: Double,
    val avgTradeReturnPct: Double,
    val bestTradePct: Double,
    val worstTradePct: Double,
    val maxDrawdownPct: Double,
    val maxConsecutiveLosses: Int,
    val profitFactor: Double?,
)

data class StrategyResearchTrade(
    val signalTime: Instant,
    val entryTime: Instant,
    val exitTime: Instant,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val returnPct: Double,
    val exitReason: StrategyResearchExitReason,
    val barsHeld: Int,
    val reason: String,
)

enum class StrategyResearchExitReason {
    TAKE_PROFIT,
    STOP_LOSS,
    TIME_EXIT,
}

private fun bd(value: Double): BigDecimal = BigDecimal.valueOf(round(value))

private fun round(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
