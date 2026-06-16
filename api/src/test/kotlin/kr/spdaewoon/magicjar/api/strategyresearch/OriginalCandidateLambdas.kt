package kr.spdaewoon.magicjar.api.strategyresearch

import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 동등성 검증 fixture — research-candidates-db 전환 *전* StrategyResearchEngine의 Kotlin 람다 18종을
 * 1:1 복제한 것(자체 추론 0, 원본 인용). [CandidateEntryEquivalenceTest]가 DSL 평가와 비교한다.
 *
 * 운영 코드가 아니라 회귀 비교 기준이다 — 원본 로직을 의도적으로 그대로 둔다.
 */
internal typealias EntryLambda = (List<ResearchCandle>, Int) -> Boolean

internal val LAMBDAS: Map<String, EntryLambda> =
    mapOf(
        "MA10_RECLAIM_SCALP" to { candles, index ->
            val previousMa10 = ma(candles, 10, index - 1)
            val currentMa10 = ma(candles, 10, index)
            val ma20 = ma(candles, 20, index)
            previousMa10 != null &&
                currentMa10 != null &&
                ma20 != null &&
                candles[index - 1].close.toDouble() < previousMa10 &&
                candles[index].close.toDouble() >= currentMa10 &&
                candles[index].close >= candles[index].open &&
                candles[index].close.toDouble() <= ma20 * 1.025
        },
        "RSI30_REBOUND_SCALP" to { candles, index ->
            val previousRsi = rsi(candles, 14, index - 1)
            val currentRsi = rsi(candles, 14, index)
            val avgVolume = avgVolume(candles, 20, index)
            previousRsi != null &&
                currentRsi != null &&
                avgVolume != null &&
                previousRsi <= 30.0 &&
                currentRsi > 30.0 &&
                candles[index].close > candles[index - 1].close &&
                candles[index].volume.toDouble() >= avgVolume * 0.6
        },
        "VOLUME_WASHOUT_REBOUND" to { candles, index ->
            val avgVolume = avgVolume(candles, 20, index - 1)
            val previousDrop = candleReturnPct(candles[index - 1])
            avgVolume != null &&
                previousDrop <= -1.2 &&
                candles[index - 1].volume.toDouble() >= avgVolume * 1.5 &&
                candles[index].close > candles[index].open &&
                candles[index].close >= candles[index - 1].close
        },
        "MA20_PULLBACK_CONTINUATION" to { candles, index ->
            val ma20 = ma(candles, 20, index)
            val ma50 = ma(candles, 50, index)
            val avgVolume = avgVolume(candles, 20, index)
            ma20 != null &&
                ma50 != null &&
                avgVolume != null &&
                ma20 > ma50 &&
                candles[index].low.toDouble() <= ma20 * 1.01 &&
                candles[index].close.toDouble() >= ma20 &&
                candles[index].close > candles[index].open &&
                candles[index].volume.toDouble() >= avgVolume * 0.7
        },
        "MA5_20_GOLDEN_CROSS" to { candles, index ->
            val previousMa5 = ma(candles, 5, index - 1)
            val previousMa20 = ma(candles, 20, index - 1)
            val currentMa5 = ma(candles, 5, index)
            val currentMa20 = ma(candles, 20, index)
            val avgVolume = avgVolume(candles, 20, index)
            previousMa5 != null &&
                previousMa20 != null &&
                currentMa5 != null &&
                currentMa20 != null &&
                avgVolume != null &&
                previousMa5 <= previousMa20 &&
                currentMa5 > currentMa20 &&
                candles[index].close > candles[index].open &&
                candles[index].volume.toDouble() >= avgVolume * 0.8
        },
        "BREAKOUT_20_HIGH_VOLUME" to { candles, index ->
            val previousHigh = rollingHigh(candles, 20, index - 1)
            val avgVolume = avgVolume(candles, 20, index)
            previousHigh != null &&
                avgVolume != null &&
                candles[index].close.toDouble() > previousHigh &&
                candles[index].volume.toDouble() >= avgVolume * 1.5 &&
                candles[index].close > candles[index].open &&
                closePosition(candles[index]) >= 0.65
        },
        "RSI40_TREND_PULLBACK" to { candles, index ->
            val ma50 = ma(candles, 50, index)
            val previousRsi = rsi(candles, 14, index - 1)
            val currentRsi = rsi(candles, 14, index)
            ma50 != null &&
                previousRsi != null &&
                currentRsi != null &&
                candles[index].close.toDouble() > ma50 &&
                previousRsi < 40.0 &&
                currentRsi >= 40.0 &&
                candles[index].close > candles[index].open
        },
        "RSI50_MOMENTUM_RECLAIM" to { candles, index ->
            val ma20 = ma(candles, 20, index)
            val previousRsi = rsi(candles, 14, index - 1)
            val currentRsi = rsi(candles, 14, index)
            val avgVolume = avgVolume(candles, 20, index)
            ma20 != null &&
                previousRsi != null &&
                currentRsi != null &&
                avgVolume != null &&
                candles[index].close.toDouble() > ma20 &&
                previousRsi < 50.0 &&
                currentRsi >= 50.0 &&
                candles[index].volume.toDouble() >= avgVolume * 0.8
        },
        "RANGE_SUPPORT_BOUNCE" to { candles, index ->
            val previousLow = rollingLow(candles, 20, index - 1)
            val currentRsi = rsi(candles, 14, index)
            previousLow != null &&
                currentRsi != null &&
                candles[index].low.toDouble() <= previousLow * 1.01 &&
                candles[index].close > candles[index].open &&
                candles[index].close > candles[index - 1].close &&
                currentRsi in 35.0..60.0
        },
        "BB_LOWER_RECLAIM" to { candles, index ->
            val previousLower = bollingerLower(candles, 20, index - 1)
            val currentLower = bollingerLower(candles, 20, index)
            val avgVolume = avgVolume(candles, 20, index)
            previousLower != null &&
                currentLower != null &&
                avgVolume != null &&
                candles[index - 1].close.toDouble() < previousLower &&
                candles[index].close.toDouble() >= currentLower &&
                candles[index].close > candles[index].open &&
                candles[index].volume.toDouble() >= avgVolume * 0.7
        },
        "DONCHIAN10_BREAKOUT" to { candles, index ->
            val previousHigh = rollingHigh(candles, 10, index - 1)
            val avgVolume = avgVolume(candles, 20, index)
            previousHigh != null &&
                avgVolume != null &&
                candles[index].close.toDouble() > previousHigh &&
                candles[index].volume.toDouble() >= avgVolume * 1.2 &&
                candles[index].close > candles[index].open &&
                closePosition(candles[index]) >= 0.6
        },
        "VOLUME_SURGE_GREEN" to { candles, index ->
            val avgVolume = avgVolume(candles, 20, index)
            avgVolume != null &&
                candles[index].volume.toDouble() >= avgVolume * 2.0 &&
                candleReturnPct(candles[index]) >= 0.8 &&
                candles[index].close > candles[index].open &&
                closePosition(candles[index]) >= 0.65
        },
        "LOW_VOLUME_PULLBACK_BREAK" to { candles, index ->
            val previousAvgVolume = avgVolume(candles, 20, index - 1)
            val currentAvgVolume = avgVolume(candles, 20, index)
            val ma20 = ma(candles, 20, index)
            previousAvgVolume != null &&
                currentAvgVolume != null &&
                ma20 != null &&
                candles[index - 1].volume.toDouble() <= previousAvgVolume * 0.7 &&
                candles[index].close > candles[index - 1].high &&
                candles[index].close.toDouble() > ma20 &&
                candles[index].volume.toDouble() >= currentAvgVolume * 1.1
        },
        "THREE_BAR_RECOVERY" to { candles, index ->
            val ma10 = ma(candles, 10, index)
            val avgVolume = avgVolume(candles, 20, index)
            index >= 2 &&
                ma10 != null &&
                avgVolume != null &&
                candles[index - 2].close < candles[index - 1].close &&
                candles[index - 1].close < candles[index].close &&
                candles[index].close.toDouble() > ma10 &&
                candles[index].close > candles[index].open &&
                candles[index].volume.toDouble() >= avgVolume * 0.7
        },
        "HAMMER_VOLUME_REVERSAL" to { candles, index ->
            val avgVolume = avgVolume(candles, 20, index)
            val body = candleBody(candles[index])
            val range = candleRange(candles[index])
            avgVolume != null &&
                range > 0.0 &&
                lowerWick(candles[index]) >= max(body, range * 0.05) * 2.0 &&
                upperWick(candles[index]) <= range * 0.35 &&
                candles[index].close > candles[index].open &&
                candles[index].volume.toDouble() >= avgVolume * 1.2
        },
        "GAP_DOWN_REVERSAL" to { candles, index ->
            val avgVolume = avgVolume(candles, 20, index)
            val previousClose = candles[index - 1].close.toDouble()
            previousClose > 0.0 &&
                avgVolume != null &&
                candles[index].open.toDouble() <= previousClose * 0.98 &&
                candles[index].close.toDouble() >= previousClose * 0.995 &&
                candles[index].close > candles[index].open &&
                candles[index].volume.toDouble() >= avgVolume
        },
        "VOLATILITY_SQUEEZE_BREAKOUT" to { candles, index ->
            val shortRange = avgRangePct(candles, 10, index - 1)
            val longRange = avgRangePct(candles, 50, index - 1)
            val previousHigh = rollingHigh(candles, 10, index - 1)
            val avgVolume = avgVolume(candles, 20, index)
            shortRange != null &&
                longRange != null &&
                previousHigh != null &&
                avgVolume != null &&
                shortRange <= longRange * 0.75 &&
                candles[index].close.toDouble() > previousHigh &&
                candles[index].volume.toDouble() >= avgVolume &&
                candles[index].close > candles[index].open
        },
        "MA50_TREND_DIP_RECLAIM" to { candles, index ->
            val previousMa20 = ma(candles, 20, index - 1)
            val currentMa20 = ma(candles, 20, index)
            val currentMa50 = ma(candles, 50, index)
            val currentRsi = rsi(candles, 14, index)
            previousMa20 != null &&
                currentMa20 != null &&
                currentMa50 != null &&
                currentRsi != null &&
                currentMa20 > currentMa50 &&
                candles[index - 1].close.toDouble() < previousMa20 &&
                candles[index].close.toDouble() >= currentMa20 &&
                candles[index].close > candles[index].open &&
                currentRsi in 45.0..65.0
        },
    )

// ===== 원본 indicator 헬퍼 1:1 복제 =====

private fun ma(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .sumOf { it.close.toDouble() }
        .div(period.toDouble())
}

private fun rsi(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period) return null
    var gains = 0.0
    var losses = 0.0
    for (cursor in (index - period + 1)..index) {
        val diff = candles[cursor].close.toDouble() - candles[cursor - 1].close.toDouble()
        if (diff >= 0.0) gains += diff else losses += abs(diff)
    }
    val averageGain = gains / period.toDouble()
    val averageLoss = losses / period.toDouble()
    if (averageLoss == 0.0) return 100.0
    val rs = averageGain / averageLoss
    return 100.0 - (100.0 / (1.0 + rs))
}

private fun avgVolume(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .sumOf { it.volume.toDouble() }
        .div(period.toDouble())
}

private fun candleReturnPct(candle: ResearchCandle): Double {
    val open = candle.open.toDouble()
    if (open == 0.0) return 0.0
    return (candle.close.toDouble() - open) / open * 100.0
}

private fun rollingHigh(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles.subList(index - period + 1, index + 1).maxOf { it.high.toDouble() }
}

private fun rollingLow(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles.subList(index - period + 1, index + 1).minOf { it.low.toDouble() }
}

private fun stdDevClose(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    val average = ma(candles, period, index) ?: return null
    val variance =
        candles
            .subList(index - period + 1, index + 1)
            .sumOf {
                val diff = it.close.toDouble() - average
                diff * diff
            }.div(period.toDouble())
    return sqrt(variance)
}

private fun bollingerLower(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
    multiplier: Double = 2.0,
): Double? {
    val middle = ma(candles, period, index) ?: return null
    val dev = stdDevClose(candles, period, index) ?: return null
    return middle - dev * multiplier
}

private fun candleRange(candle: ResearchCandle): Double = candle.high.toDouble() - candle.low.toDouble()

private fun candleBody(candle: ResearchCandle): Double = abs(candle.close.toDouble() - candle.open.toDouble())

private fun lowerWick(candle: ResearchCandle): Double = min(candle.open.toDouble(), candle.close.toDouble()) - candle.low.toDouble()

private fun upperWick(candle: ResearchCandle): Double = candle.high.toDouble() - max(candle.open.toDouble(), candle.close.toDouble())

private fun closePosition(candle: ResearchCandle): Double {
    val range = candleRange(candle)
    if (range <= 0.0) return 0.5
    return (candle.close.toDouble() - candle.low.toDouble()) / range
}

private fun avgRangePct(
    candles: List<ResearchCandle>,
    period: Int,
    index: Int,
): Double? {
    if (index < period - 1) return null
    return candles
        .subList(index - period + 1, index + 1)
        .mapNotNull { candle ->
            val close = candle.close.toDouble()
            if (close <= 0.0) null else candleRange(candle) / close * 100.0
        }.takeIf { it.isNotEmpty() }
        ?.average()
}
