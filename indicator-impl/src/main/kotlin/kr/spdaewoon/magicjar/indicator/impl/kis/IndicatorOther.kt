package kr.spdaewoon.magicjar.indicator.impl.kis

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.kis.KisIndicatorMath.norm
import java.math.BigDecimal
import kotlin.math.ln

/**
 * KIS Strategy Builder OTHER 8지표 Kotlin port.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 */
internal object IndicatorOther {
    private const val SOURCE = "kis-strategy-builder"

    /** calc_std indicators.py:33-48 — sample std (ddof=1) */
    fun std(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val out = KisIndicatorMath.rollingStd(KisIndicatorMath.closes(candles), period)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "std"), SOURCE)
    }

    /** calc_variance indicators.py:1395-1399 */
    fun variance(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val out = KisIndicatorMath.rollingVariance(KisIndicatorMath.closes(candles), period)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "variance"), SOURCE)
    }

    /** calc_disparity indicators.py:68-86 — close / SMA(period) * 100 */
    fun disparity(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val sma = KisIndicatorMath.rollingMean(closes, period)
        val out =
            candles.indices.map { i ->
                val s = sma[i]
                if (s == null || s.signum() == 0) {
                    null
                } else {
                    closes[i].divide(s, KisIndicatorMath.MC).multiply(BigDecimal.valueOf(100), KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "disparity"), SOURCE)
    }

    /** calc_midpoint indicators.py:1441-1445 — (close.max + close.min)/2 */
    fun midpoint(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val mx = KisIndicatorMath.rollingMax(closes, period)
        val mn = KisIndicatorMath.rollingMin(closes, period)
        val out =
            candles.indices.map { i ->
                val a = mx[i]
                val b = mn[i]
                if (a == null || b == null) {
                    null
                } else {
                    a.add(b, KisIndicatorMath.MC).divide(BigDecimal.valueOf(2), KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "midpoint"), SOURCE)
    }

    /** calc_midprice indicators.py:1448-1452 — (high.max + low.min)/2 */
    fun midprice(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val mx = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        val mn = KisIndicatorMath.rollingMin(KisIndicatorMath.lows(candles), period)
        val out =
            candles.indices.map { i ->
                val a = mx[i]
                val b = mn[i]
                if (a == null || b == null) {
                    null
                } else {
                    a.add(b, KisIndicatorMath.MC).divide(BigDecimal.valueOf(2), KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "midprice"), SOURCE)
    }

    /** calc_logr indicators.py:1455-1459 — log(close / close.shift(1)) */
    fun logr(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.size < 2) throw IndicatorException.InsufficientCandlesException(2, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val series =
            candles.indices.mapNotNull { i ->
                if (i == 0) {
                    null
                } else {
                    val prev = closes[i - 1]
                    if (prev.signum() <= 0) {
                        null
                    } else {
                        IndicatorPoint(
                            candles[i].ts,
                            mapOf("logr" to BigDecimal.valueOf(ln(closes[i].toDouble() / prev.toDouble())).norm()),
                        )
                    }
                }
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_pivot indicators.py:1494-1498 — typical price (h+l+c)/3, plus r1·r2·r3·s1·s2·s3 standard pivot */
    fun pivot(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.isEmpty()) throw IndicatorException.InsufficientCandlesException(1, 0)
        val three = BigDecimal.valueOf(3)
        val two = BigDecimal.valueOf(2)
        val series =
            candles.map { c ->
                val p =
                    c.highRaw
                        .add(c.lowRaw, KisIndicatorMath.MC)
                        .add(c.closeRaw, KisIndicatorMath.MC)
                        .divide(three, KisIndicatorMath.MC)
                val range = c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC)
                val r1 = p.multiply(two, KisIndicatorMath.MC).subtract(c.lowRaw, KisIndicatorMath.MC)
                val s1 = p.multiply(two, KisIndicatorMath.MC).subtract(c.highRaw, KisIndicatorMath.MC)
                val r2 = p.add(range, KisIndicatorMath.MC)
                val s2 = p.subtract(range, KisIndicatorMath.MC)
                val r3 = c.highRaw.add(range.multiply(two, KisIndicatorMath.MC), KisIndicatorMath.MC)
                val s3 = c.lowRaw.subtract(range.multiply(two, KisIndicatorMath.MC), KisIndicatorMath.MC)
                IndicatorPoint(
                    c.ts,
                    mapOf(
                        "p" to p.norm(),
                        "r1" to r1.norm(),
                        "r2" to r2.norm(),
                        "r3" to r3.norm(),
                        "s1" to s1.norm(),
                        "s2" to s2.norm(),
                        "s3" to s3.norm(),
                    ),
                )
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_regression_intercept indicators.py:1481-1491 */
    fun regressionIntercept(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles).map { it.toDouble() }
        val xMean = (0 until period).sumOf { it.toDouble() } / period
        var xVar = 0.0
        for (i in 0 until period) xVar += (i - xMean) * (i - xMean)
        val out = ArrayList<BigDecimal?>(closes.size)
        for (i in closes.indices) {
            if (i < period - 1) {
                out.add(null)
                continue
            }
            val window = closes.subList(i - period + 1, i + 1)
            val yMean = window.sum() / period
            var num = 0.0
            for (k in 0 until period) num += (k - xMean) * (window[k] - yMean)
            val slope = num / xVar
            val intercept = yMean - slope * xMean
            out.add(BigDecimal.valueOf(intercept))
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "intercept"), SOURCE)
    }
}
