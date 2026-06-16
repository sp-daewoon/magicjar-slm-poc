package kr.spdaewoon.magicjar.indicator.impl.kis

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.kis.KisIndicatorMath.norm
import java.math.BigDecimal

/**
 * KIS Strategy Builder VOLUME 12지표 Kotlin port.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 */
internal object IndicatorVolume {
    private const val SOURCE = "kis-strategy-builder"

    /** calc_obv indicators.py:616-640 */
    fun obv(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.size < 2) throw IndicatorException.InsufficientCandlesException(2, candles.size)
        val obv = DoubleArray(candles.size)
        obv[0] = candles[0].volume.toDouble()
        for (i in 1 until candles.size) {
            val c = candles[i].closeRaw.compareTo(candles[i - 1].closeRaw)
            obv[i] =
                when {
                    c > 0 -> obv[i - 1] + candles[i].volume
                    c < 0 -> obv[i - 1] - candles[i].volume
                    else -> obv[i - 1]
                }
        }
        val series =
            obv.indices.map { i ->
                IndicatorPoint(candles[i].ts, mapOf("obv" to BigDecimal.valueOf(obv[i]).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_volume_ma indicators.py:643-657 */
    fun volumeMa(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val volumes = candles.map { BigDecimal.valueOf(it.volume) }
        val sma = KisIndicatorMath.rollingMean(volumes, period)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, sma, "volume_ma"), SOURCE)
    }

    /** calc_vwap indicators.py:660-677 — cumulative TP*Vol / cumulative Vol */
    fun vwap(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.isEmpty()) throw IndicatorException.InsufficientCandlesException(1, 0)
        var cumPv = BigDecimal.ZERO
        var cumV = BigDecimal.ZERO
        val series =
            candles.map { c ->
                val tp =
                    c.highRaw
                        .add(c.lowRaw)
                        .add(c.closeRaw, KisIndicatorMath.MC)
                        .divide(BigDecimal.valueOf(3), KisIndicatorMath.MC)
                val vol = BigDecimal.valueOf(c.volume)
                cumPv = cumPv.add(tp.multiply(vol, KisIndicatorMath.MC), KisIndicatorMath.MC)
                cumV = cumV.add(vol, KisIndicatorMath.MC)
                val v = if (cumV.signum() == 0) BigDecimal.ZERO else cumPv.divide(cumV, KisIndicatorMath.MC)
                IndicatorPoint(c.ts, mapOf("vwap" to v.norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_mfi indicators.py:680-717 */
    fun mfi(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val tp =
            candles.map { c ->
                c.highRaw
                    .add(c.lowRaw)
                    .add(c.closeRaw, KisIndicatorMath.MC)
                    .divide(BigDecimal.valueOf(3), KisIndicatorMath.MC)
            }
        val rmf = candles.indices.map { i -> tp[i].multiply(BigDecimal.valueOf(candles[i].volume), KisIndicatorMath.MC) }
        val tpDiff = KisIndicatorMath.diff(tp)
        val pos = candles.indices.map { i -> if (tpDiff[i].signum() > 0) rmf[i] else BigDecimal.ZERO }
        val neg = candles.indices.map { i -> if (tpDiff[i].signum() < 0) rmf[i] else BigDecimal.ZERO }
        val posSum = KisIndicatorMath.rollingSum(pos, period)
        val negSum = KisIndicatorMath.rollingSum(neg, period)
        val out =
            candles.indices.map { i ->
                val p = posSum[i]
                val n = negSum[i]
                if (p == null || n == null) {
                    null
                } else if (n.signum() == 0) {
                    BigDecimal.valueOf(100)
                } else {
                    val mr = p.divide(n, KisIndicatorMath.MC)
                    BigDecimal
                        .valueOf(
                            100,
                        ).subtract(
                            BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(mr, KisIndicatorMath.MC), KisIndicatorMath.MC),
                            KisIndicatorMath.MC,
                        )
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "mfi"), SOURCE)
    }

    /** calc_cmf indicators.py:974-981 */
    fun cmf(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val mfm =
            candles.map { c ->
                val denom = c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC)
                if (denom.signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    c.closeRaw
                        .subtract(c.lowRaw, KisIndicatorMath.MC)
                        .subtract(c.highRaw.subtract(c.closeRaw, KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .divide(denom, KisIndicatorMath.MC)
                }
            }
        val mfv = candles.indices.map { i -> mfm[i].multiply(BigDecimal.valueOf(candles[i].volume), KisIndicatorMath.MC) }
        val volumes = candles.map { BigDecimal.valueOf(it.volume) }
        val mfvSum = KisIndicatorMath.rollingSum(mfv, period)
        val volSum = KisIndicatorMath.rollingSum(volumes, period)
        val out =
            candles.indices.map { i ->
                val m = mfvSum[i]
                val v = volSum[i]
                if (m == null || v == null || v.signum() == 0) {
                    null
                } else {
                    m.divide(v, KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "cmf"), SOURCE)
    }

    /** calc_cho indicators.py:1160-1167 — ADL의 fast/slow EMA 차이 */
    fun cho(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val fast = spec.intParam("fast", 3)
        val slow = spec.intParam("slow", 10)
        if (candles.size < slow) throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        val adl = computeAdl(candles)
        val emaFast = KisIndicatorMath.ewm(adl, fast)
        val emaSlow = KisIndicatorMath.ewm(adl, slow)
        val series =
            candles.indices.map { i ->
                IndicatorPoint(candles[i].ts, mapOf("cho" to emaFast[i].subtract(emaSlow[i], KisIndicatorMath.MC).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_ad indicators.py:1350-1356 — single bar AD */
    fun ad(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.isEmpty()) throw IndicatorException.InsufficientCandlesException(1, 0)
        val series =
            candles.map { c ->
                val hl = c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC)
                val mfm =
                    if (hl.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        c.closeRaw
                            .subtract(c.lowRaw, KisIndicatorMath.MC)
                            .subtract(c.highRaw.subtract(c.closeRaw, KisIndicatorMath.MC), KisIndicatorMath.MC)
                            .divide(hl, KisIndicatorMath.MC)
                    }
                IndicatorPoint(c.ts, mapOf("ad" to mfm.multiply(BigDecimal.valueOf(c.volume), KisIndicatorMath.MC).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_adl indicators.py:1359-1364 — AD의 누적합 */
    fun adl(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.isEmpty()) throw IndicatorException.InsufficientCandlesException(1, 0)
        val adl = computeAdl(candles)
        val series =
            candles.indices.map { i ->
                IndicatorPoint(candles[i].ts, mapOf("adl" to adl[i].norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    private fun computeAdl(candles: List<Candle>): List<BigDecimal> {
        var cum = BigDecimal.ZERO
        return candles.map { c ->
            val hl = c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC)
            val mfm =
                if (hl.signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    c.closeRaw
                        .subtract(c.lowRaw, KisIndicatorMath.MC)
                        .subtract(c.highRaw.subtract(c.closeRaw, KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .divide(hl, KisIndicatorMath.MC)
                }
            cum = cum.add(mfm.multiply(BigDecimal.valueOf(c.volume), KisIndicatorMath.MC), KisIndicatorMath.MC)
            cum
        }
    }

    /** calc_force indicators.py:1367-1372 — close.diff() * volume 의 EMA */
    fun force(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 13)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val diff = KisIndicatorMath.diff(KisIndicatorMath.closes(candles))
        val fi = candles.indices.map { i -> diff[i].multiply(BigDecimal.valueOf(candles[i].volume), KisIndicatorMath.MC) }
        val ema = KisIndicatorMath.ewm(fi, period)
        val series = ema.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("force" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_vwma indicators.py:1375-1380 — sum(close*vol)/sum(vol) over period */
    fun vwma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val pv = candles.map { c -> c.closeRaw.multiply(BigDecimal.valueOf(c.volume), KisIndicatorMath.MC) }
        val volumes = candles.map { BigDecimal.valueOf(it.volume) }
        val pvSum = KisIndicatorMath.rollingSum(pv, period)
        val volSum = KisIndicatorMath.rollingSum(volumes, period)
        val out =
            candles.indices.map { i ->
                val p = pvSum[i]
                val v = volSum[i]
                if (p == null || v == null || v.signum() == 0) {
                    null
                } else {
                    p.divide(v, KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "vwma"), SOURCE)
    }

    /** calc_eom indicators.py:1383-1389 */
    fun eom(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val two = BigDecimal.valueOf(2)
        val midSeries = candles.map { c -> c.highRaw.add(c.lowRaw, KisIndicatorMath.MC).divide(two, KisIndicatorMath.MC) }
        val midShift = KisIndicatorMath.shift(midSeries, 1)
        val eom =
            candles.indices.map { i ->
                val prev = midShift[i] ?: return@map BigDecimal.ZERO
                val dm = midSeries[i].subtract(prev, KisIndicatorMath.MC)
                val hl = candles[i].highRaw.subtract(candles[i].lowRaw, KisIndicatorMath.MC)
                if (hl.signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    val br = BigDecimal.valueOf(candles[i].volume).divide(hl, KisIndicatorMath.MC)
                    if (br.signum() == 0) BigDecimal.ZERO else dm.divide(br, KisIndicatorMath.MC)
                }
            }
        val sma = KisIndicatorMath.rollingMean(eom, period)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, sma, "eom"), SOURCE)
    }

    /** calc_kvo indicators.py:1223-1235 */
    fun kvo(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val fast = spec.intParam("fast", 34)
        val slow = spec.intParam("slow", 55)
        if (candles.size < slow) throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        val n = candles.size
        val hlc =
            candles.map { c ->
                c.highRaw
                    .add(c.lowRaw, KisIndicatorMath.MC)
                    .add(c.closeRaw, KisIndicatorMath.MC)
                    .toDouble()
            }
        val trend = IntArray(n) { 0 }
        for (i in 1 until n) trend[i] = if (hlc[i] > hlc[i - 1]) 1 else -1
        val dm = candles.map { it.highRaw.subtract(it.lowRaw, KisIndicatorMath.MC).toDouble() }
        val cm = DoubleArray(n)
        cm[0] = dm[0]
        for (i in 1 until n) {
            cm[i] = if (trend[i] == trend[i - 1]) cm[i - 1] + dm[i] else dm[i - 1] + dm[i]
        }
        val vf = DoubleArray(n)
        for (i in 0 until n) {
            val cmI = cm[i]
            val ratio = if (cmI == 0.0) 0.0 else kotlin.math.abs(2 * dm[i] / cmI - 1)
            vf[i] = candles[i].volume * ratio * trend[i] * 100
        }
        val vfList = vf.map { BigDecimal.valueOf(it) }
        val emaFast = KisIndicatorMath.ewm(vfList, fast)
        val emaSlow = KisIndicatorMath.ewm(vfList, slow)
        val series =
            candles.indices.map { i ->
                IndicatorPoint(candles[i].ts, mapOf("kvo" to emaFast[i].subtract(emaSlow[i], KisIndicatorMath.MC).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }
}
