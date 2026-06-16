package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 볼린저 밴드(BB) — magicJar Native 자체 구현.
 *
 * KIS calc_bb_middle / calc_bb_upper / calc_bb_lower (indicators.py:392-466) 1:1 cross-check 정합:
 *   - middle = SMA(close, period)
 *   - std = sample std (ddof=1) over rolling window
 *   - upper = middle + std * std_dev
 *   - lower = middle - std * std_dev
 *
 * 결과 series.size = candles.size - period + 1 (warm-up trimmed).
 *
 * Multi-output keys: §5-2 매트릭스 LOCK — {upper, middle, lower}.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py:392-466
 * License: MIT
 */
internal object NativeBollingerBands {
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        val stdDev = spec.bdParam("std_dev", BigDecimal.valueOf(2.0))
        if (candles.size < period) {
            throw IndicatorException.InsufficientCandlesException(period, candles.size)
        }
        val closes = IndicatorMath.closes(candles)
        val middle = IndicatorMath.smaTrimmed(closes, period)
        val std = IndicatorMath.rollingStdSample(closes, period)
        val series =
            middle.indices.map { i ->
                val m = middle[i]
                val s = std[i]
                val band = s.multiply(stdDev, IndicatorMath.MC)
                IndicatorPoint(
                    time = candles[i + period - 1].ts,
                    values =
                        mapOf(
                            "upper" to m.add(band, IndicatorMath.MC).setScale(IndicatorMath.SCALE, RoundingMode.HALF_EVEN),
                            "middle" to m,
                            "lower" to m.subtract(band, IndicatorMath.MC).setScale(IndicatorMath.SCALE, RoundingMode.HALF_EVEN),
                        ),
                )
            }
        return IndicatorResult(spec, series, "native")
    }
}
