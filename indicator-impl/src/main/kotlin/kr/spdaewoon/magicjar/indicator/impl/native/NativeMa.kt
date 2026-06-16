package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 단순이동평균(SMA) — magicJar Native 자체 구현.
 *
 * KIS calc_ma (indicators.py:15-30) 1:1 cross-check 정합:
 *   - `df[column].rolling(window=period).mean()`
 *   - 결과 첫 (period-1)개 NaN — 본 구현은 series.size = candles.size - period + 1 (warm-up trimmed)
 *
 * Multi-output keys: §5-1 매트릭스 정합 — {ma}.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py:15-30
 * License: MIT
 */
internal object NativeMa {
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) {
            throw IndicatorException.InsufficientCandlesException(period, candles.size)
        }
        val closes = IndicatorMath.closes(candles)
        val sma = IndicatorMath.smaTrimmed(closes, period)
        val series =
            sma.mapIndexed { idx, value ->
                IndicatorPoint(
                    time = candles[idx + period - 1].ts,
                    values = mapOf("ma" to value),
                )
            }
        return IndicatorResult(spec, series, "native")
    }
}
