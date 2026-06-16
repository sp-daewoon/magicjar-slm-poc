package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.RoundingMode

/**
 * MACD (Moving Average Convergence Divergence) — magicJar Native 자체 구현.
 *
 * KIS calc_macd / calc_macd_signal / calc_macd_histogram (indicators.py:312-389) 1:1 cross-check 정합:
 *   - macd = EMA(close, fast) - EMA(close, slow)
 *   - signal = EMA(macd, signal_period)
 *   - histogram = macd - signal
 *
 * Multi-output keys: §5-2 매트릭스 LOCK — {macd, signal, histogram}.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py:312-389
 * License: MIT
 */
internal object NativeMacd {
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val fast = spec.intParam("fast", 12)
        val slow = spec.intParam("slow", 26)
        val signalPeriod = spec.intParam("signal", 9)
        if (candles.size < slow) {
            throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        }
        val closes = IndicatorMath.closes(candles)
        val emaFast = IndicatorMath.ema(closes, fast)
        val emaSlow = IndicatorMath.ema(closes, slow)
        val macd = emaFast.zip(emaSlow) { f, s -> f.subtract(s, IndicatorMath.MC) }
        val signal = IndicatorMath.ema(macd, signalPeriod)
        val histogram =
            macd.zip(signal) { m, s ->
                m.subtract(s, IndicatorMath.MC).setScale(IndicatorMath.SCALE, RoundingMode.HALF_EVEN)
            }
        val series =
            closes.indices.map { i ->
                IndicatorPoint(
                    time = candles[i].ts,
                    values =
                        mapOf(
                            "macd" to macd[i].setScale(IndicatorMath.SCALE, RoundingMode.HALF_EVEN),
                            "signal" to signal[i].setScale(IndicatorMath.SCALE, RoundingMode.HALF_EVEN),
                            "histogram" to histogram[i],
                        ),
                )
            }
        return IndicatorResult(spec, series, "native")
    }
}
