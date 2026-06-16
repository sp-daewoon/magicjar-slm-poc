package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 지수이동평균(EMA) — magicJar Native 자체 구현.
 *
 * KIS calc_ema (indicators.py:264-279) 1:1 cross-check 정합:
 *   - `df[column].ewm(span=period, adjust=False).mean()`
 *   - α = 2 / (period + 1), EMA_t = α * x_t + (1 - α) * EMA_{t-1}
 *   - EMA_0 = x_0 (adjust=False 정합)
 *
 * Multi-output keys: §5-1 매트릭스 정합 — {ema}.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py:264-279
 * License: MIT
 */
internal object NativeEma {
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) {
            throw IndicatorException.InsufficientCandlesException(period, candles.size)
        }
        val closes = IndicatorMath.closes(candles)
        val ema = IndicatorMath.ema(closes, period)
        val series =
            ema.mapIndexed { idx, value ->
                IndicatorPoint(
                    time = candles[idx].ts,
                    values = mapOf("ema" to value),
                )
            }
        return IndicatorResult(spec, series, "native")
    }
}
