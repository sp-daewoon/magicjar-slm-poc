package kr.spdaewoon.magicjar.indicator.impl.native

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * RSI(Relative Strength Index) — magicJar Native 자체 구현.
 *
 * KIS calc_rsi (indicators.py:282-309) 1:1 cross-check 정합:
 *   - delta = close.diff()
 *   - gain = delta.where(delta > 0, 0)
 *   - loss = (-delta).where(delta < 0, 0)
 *   - avg_gain = gain.ewm(span=period, adjust=False).mean()  // **EWM, not Wilder**
 *   - avg_loss = loss.ewm(span=period, adjust=False).mean()
 *   - RS = avg_gain / avg_loss, RSI = 100 - 100 / (1 + RS)
 *
 * 주의: KIS는 표준 Wilder RSI(Smoothed MA)가 아닌 EWM RSI를 사용. 본 Native도 KIS 정합으로 EWM 사용.
 *
 * Multi-output keys: §5-1 매트릭스 정합 — {rsi}.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py:282-309
 * License: MIT
 */
internal object NativeRsi {
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) {
            throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        }
        val closes = IndicatorMath.closes(candles)
        val gains = ArrayList<BigDecimal>(closes.size)
        val losses = ArrayList<BigDecimal>(closes.size)
        gains.add(BigDecimal.ZERO)
        losses.add(BigDecimal.ZERO)
        for (i in 1 until closes.size) {
            val delta = closes[i].subtract(closes[i - 1], IndicatorMath.MC)
            if (delta.signum() > 0) {
                gains.add(delta)
                losses.add(BigDecimal.ZERO)
            } else if (delta.signum() < 0) {
                gains.add(BigDecimal.ZERO)
                losses.add(delta.negate())
            } else {
                gains.add(BigDecimal.ZERO)
                losses.add(BigDecimal.ZERO)
            }
        }
        val avgGain = IndicatorMath.ema(gains, period)
        val avgLoss = IndicatorMath.ema(losses, period)
        val hundred = BigDecimal.valueOf(100)
        val series = ArrayList<IndicatorPoint>(closes.size)
        for (i in closes.indices) {
            val ag = avgGain[i]
            val al = avgLoss[i]
            val rsi =
                when {
                    al.signum() == 0 && ag.signum() == 0 -> BigDecimal.valueOf(50)
                    al.signum() == 0 -> hundred
                    else -> {
                        val rs = ag.divide(al, IndicatorMath.MC)
                        hundred.subtract(
                            hundred.divide(BigDecimal.ONE.add(rs, IndicatorMath.MC), IndicatorMath.MC),
                            IndicatorMath.MC,
                        )
                    }
                }.setScale(IndicatorMath.SCALE, RoundingMode.HALF_EVEN)
            series.add(
                IndicatorPoint(
                    time = candles[i].ts,
                    values = mapOf("rsi" to rsi),
                ),
            )
        }
        return IndicatorResult(spec, series, "native")
    }
}
