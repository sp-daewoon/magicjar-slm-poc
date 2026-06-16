package kr.spdaewoon.magicjar.indicator.impl.kis

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.kis.KisIndicatorMath.norm
import java.math.BigDecimal

/**
 * KIS Strategy Builder OSCILLATOR 8지표 Kotlin port.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 */
internal object IndicatorOscillator {
    private const val SOURCE = "kis-strategy-builder"
    private val HUNDRED = BigDecimal.valueOf(100)

    /** calc_adx indicators.py:720-760 */
    fun adx(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period * 2) throw IndicatorException.InsufficientCandlesException(period * 2, candles.size)
        val series = computeAdxSeries(candles, period)
        val outSeries = series.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("adx" to v.norm())) }
        return IndicatorResult(spec, outSeries, SOURCE)
    }

    /** calc_adxr indicators.py:1240-1245 — (adx + adx.shift(period)) / 2 */
    fun adxr(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period * 2) throw IndicatorException.InsufficientCandlesException(period * 2, candles.size)
        val adx = computeAdxSeries(candles, period)
        val shifted = KisIndicatorMath.shift(adx, period)
        val series =
            candles.indices.mapNotNull { i ->
                val s = shifted[i] ?: return@mapNotNull null
                IndicatorPoint(
                    candles[i].ts,
                    mapOf(
                        "adxr" to adx[i].add(s, KisIndicatorMath.MC).divide(BigDecimal.valueOf(2), KisIndicatorMath.MC).norm(),
                    ),
                )
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    private fun computeAdxSeries(
        candles: List<Candle>,
        period: Int,
    ): List<BigDecimal> {
        val high = KisIndicatorMath.highs(candles)
        val low = KisIndicatorMath.lows(candles)
        val plusDmRaw = ArrayList<BigDecimal>(candles.size)
        val minusDmRaw = ArrayList<BigDecimal>(candles.size)
        plusDmRaw.add(BigDecimal.ZERO)
        minusDmRaw.add(BigDecimal.ZERO)
        for (i in 1 until candles.size) {
            val plus = high[i].subtract(high[i - 1], KisIndicatorMath.MC)
            val minus = low[i - 1].subtract(low[i], KisIndicatorMath.MC)
            // pandas: plus.where((plus > minus) & (plus > 0), 0)
            val pdm = if (plus > minus && plus.signum() > 0) plus else BigDecimal.ZERO
            val mdm = if (minus > plus && minus.signum() > 0) minus else BigDecimal.ZERO
            plusDmRaw.add(pdm)
            minusDmRaw.add(mdm)
        }
        val tr = KisIndicatorMath.trueRange(candles)
        val atr = KisIndicatorMath.ewm(tr, period)
        val plusDm = KisIndicatorMath.ewm(plusDmRaw, period)
        val minusDm = KisIndicatorMath.ewm(minusDmRaw, period)
        val plusDi =
            candles.indices.map { i ->
                if (atr[i].signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    plusDm[i].divide(atr[i], KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                }
            }
        val minusDi =
            candles.indices.map { i ->
                if (atr[i].signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    minusDm[i].divide(atr[i], KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                }
            }
        val dx =
            candles.indices.map { i ->
                val sum = plusDi[i].add(minusDi[i], KisIndicatorMath.MC)
                if (sum.signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    plusDi[i]
                        .subtract(minusDi[i], KisIndicatorMath.MC)
                        .abs()
                        .divide(sum, KisIndicatorMath.MC)
                        .multiply(HUNDRED, KisIndicatorMath.MC)
                }
            }
        return KisIndicatorMath.ewm(dx, period)
    }

    /** calc_aroon_up indicators.py:804-813 */
    fun aroonUp(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 25)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val high = candles.map { it.highRaw.toDouble() }
        val out = ArrayList<BigDecimal?>(candles.size)
        for (i in candles.indices) {
            if (i < period) {
                out.add(null)
                continue
            }
            // window iloc[i - period : i + 1] (size period+1)
            var maxIdx = 0
            var maxVal = high[i - period]
            for (k in 1..period) {
                val idx = i - period + k
                if (high[idx] > maxVal) {
                    maxVal = high[idx]
                    maxIdx = k
                }
            }
            val daysSinceHigh = period - maxIdx
            out.add(BigDecimal.valueOf(((period - daysSinceHigh).toDouble() / period) * 100))
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "up"), SOURCE)
    }

    /** calc_aroon_down indicators.py:816-825 */
    fun aroonDown(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 25)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val low = candles.map { it.lowRaw.toDouble() }
        val out = ArrayList<BigDecimal?>(candles.size)
        for (i in candles.indices) {
            if (i < period) {
                out.add(null)
                continue
            }
            var minIdx = 0
            var minVal = low[i - period]
            for (k in 1..period) {
                val idx = i - period + k
                if (low[idx] < minVal) {
                    minVal = low[idx]
                    minIdx = k
                }
            }
            val daysSinceLow = period - minIdx
            out.add(BigDecimal.valueOf(((period - daysSinceLow).toDouble() / period) * 100))
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "down"), SOURCE)
    }

    /** calc_vortex_plus indicators.py:1248-1256 */
    fun vortexPlus(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val vmPlus =
            candles.indices.map { i ->
                if (i == 0) {
                    BigDecimal.ZERO
                } else {
                    candles[i].highRaw.subtract(candles[i - 1].lowRaw, KisIndicatorMath.MC).abs()
                }
            }
        val tr = KisIndicatorMath.trueRange(candles)
        val vmSum = KisIndicatorMath.rollingSum(vmPlus, period)
        val trSum = KisIndicatorMath.rollingSum(tr, period)
        val out =
            candles.indices.map { i ->
                val v = vmSum[i]
                val t = trSum[i]
                if (v == null || t == null || t.signum() == 0) null else v.divide(t, KisIndicatorMath.MC)
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "plus"), SOURCE)
    }

    /** calc_vortex_minus indicators.py:1259-1267 */
    fun vortexMinus(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val vmMinus =
            candles.indices.map { i ->
                if (i == 0) {
                    BigDecimal.ZERO
                } else {
                    candles[i].lowRaw.subtract(candles[i - 1].highRaw, KisIndicatorMath.MC).abs()
                }
            }
        val tr = KisIndicatorMath.trueRange(candles)
        val vmSum = KisIndicatorMath.rollingSum(vmMinus, period)
        val trSum = KisIndicatorMath.rollingSum(tr, period)
        val out =
            candles.indices.map { i ->
                val v = vmSum[i]
                val t = trSum[i]
                if (v == null || t == null || t.signum() == 0) null else v.divide(t, KisIndicatorMath.MC)
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "minus"), SOURCE)
    }

    /** calc_ultosc indicators.py:1170-1181 */
    fun ultosc(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val p1 = spec.intParam("p1", 7)
        val p2 = spec.intParam("p2", 14)
        val p3 = spec.intParam("p3", 28)
        if (candles.size < p3) throw IndicatorException.InsufficientCandlesException(p3, candles.size)
        val tl = ArrayList<BigDecimal>(candles.size)
        val bp = ArrayList<BigDecimal>(candles.size)
        val tr = ArrayList<BigDecimal>(candles.size)
        for (i in candles.indices) {
            val pc = if (i == 0) candles[i].closeRaw else candles[i - 1].closeRaw
            val low = candles[i].lowRaw
            val high = candles[i].highRaw
            val close = candles[i].closeRaw
            val tlV = if (low < pc) low else pc
            val highMax = if (high > pc) high else pc
            tl.add(tlV)
            bp.add(close.subtract(tlV, KisIndicatorMath.MC))
            tr.add(highMax.subtract(tlV, KisIndicatorMath.MC))
        }
        val bpSum1 = KisIndicatorMath.rollingSum(bp, p1)
        val bpSum2 = KisIndicatorMath.rollingSum(bp, p2)
        val bpSum3 = KisIndicatorMath.rollingSum(bp, p3)
        val trSum1 = KisIndicatorMath.rollingSum(tr, p1)
        val trSum2 = KisIndicatorMath.rollingSum(tr, p2)
        val trSum3 = KisIndicatorMath.rollingSum(tr, p3)
        val out =
            candles.indices.map { i ->
                val a1 = bpSum1[i]
                val t1 = trSum1[i]
                val a2 = bpSum2[i]
                val t2 = trSum2[i]
                val a3 = bpSum3[i]
                val t3 = trSum3[i]
                if (a1 == null || t1 == null || a2 == null || t2 == null || a3 == null || t3 == null) {
                    null
                } else if (t1.signum() == 0 || t2.signum() == 0 || t3.signum() == 0) {
                    null
                } else {
                    val avg1 = a1.divide(t1, KisIndicatorMath.MC)
                    val avg2 = a2.divide(t2, KisIndicatorMath.MC)
                    val avg3 = a3.divide(t3, KisIndicatorMath.MC)
                    BigDecimal
                        .valueOf(100)
                        .multiply(
                            avg1
                                .multiply(BigDecimal.valueOf(4), KisIndicatorMath.MC)
                                .add(avg2.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC), KisIndicatorMath.MC)
                                .add(avg3, KisIndicatorMath.MC),
                            KisIndicatorMath.MC,
                        ).divide(BigDecimal.valueOf(7), KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "uo"), SOURCE)
    }

    /** calc_bop indicators.py:1462-1466 */
    fun bop(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.isEmpty()) throw IndicatorException.InsufficientCandlesException(1, 0)
        val series =
            candles.map { c ->
                val denom = c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC)
                val v =
                    if (denom.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        c.closeRaw.subtract(c.openRaw, KisIndicatorMath.MC).divide(denom, KisIndicatorMath.MC)
                    }
                IndicatorPoint(c.ts, mapOf("bop" to v.norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }
}
