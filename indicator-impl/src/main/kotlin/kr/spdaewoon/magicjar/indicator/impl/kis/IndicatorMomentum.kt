package kr.spdaewoon.magicjar.indicator.impl.kis

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.kis.KisIndicatorMath.norm
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln

/**
 * KIS Strategy Builder MOMENTUM 20지표 Kotlin port.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 */
internal object IndicatorMomentum {
    private const val SOURCE = "kis-strategy-builder"
    private val HUNDRED = BigDecimal.valueOf(100)

    /** calc_rsi indicators.py:282-309 */
    fun rsi(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val delta = KisIndicatorMath.diff(closes)
        val gains = delta.map { if (it.signum() > 0) it else BigDecimal.ZERO }
        val losses = delta.map { if (it.signum() < 0) it.negate() else BigDecimal.ZERO }
        val avgGain = KisIndicatorMath.ewm(gains, period)
        val avgLoss = KisIndicatorMath.ewm(losses, period)
        val series =
            closes.indices.map { i ->
                val rsi =
                    when {
                        avgLoss[i].signum() == 0 && avgGain[i].signum() == 0 -> BigDecimal.valueOf(50)
                        avgLoss[i].signum() == 0 -> HUNDRED
                        else -> {
                            val rs = avgGain[i].divide(avgLoss[i], KisIndicatorMath.MC)
                            HUNDRED.subtract(
                                HUNDRED.divide(BigDecimal.ONE.add(rs, KisIndicatorMath.MC), KisIndicatorMath.MC),
                                KisIndicatorMath.MC,
                            )
                        }
                    }.norm()
                IndicatorPoint(candles[i].ts, mapOf("rsi" to rsi))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_stochastic_k indicators.py:490-509 */
    fun stochasticK(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val k = computeStochasticK(candles, period)
        val out =
            k
                .mapIndexed { i, v ->
                    v?.let { IndicatorPoint(candles[i].ts, mapOf("k" to it.norm())) }
                }.filterNotNull()
        return IndicatorResult(spec, out, SOURCE)
    }

    /** calc_stochastic_d indicators.py:512-529 — k의 SMA(d_period) */
    fun stochasticD(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val kPeriod = spec.intParam("k_period", 14)
        val dPeriod = spec.intParam("d_period", 3)
        if (candles.size < kPeriod) throw IndicatorException.InsufficientCandlesException(kPeriod, candles.size)
        val k = computeStochasticK(candles, kPeriod)
        // k 의 rolling mean
        val out = ArrayList<IndicatorPoint>()
        for (i in candles.indices) {
            val kv = k[i]
            val end = i
            if (kv == null || end < kPeriod - 1 + dPeriod - 1) continue
            var sum = BigDecimal.ZERO
            var ok = true
            for (j in end - dPeriod + 1..end) {
                val v = k[j]
                if (v == null) {
                    ok = false
                    break
                }
                sum = sum.add(v, KisIndicatorMath.MC)
            }
            if (!ok) continue
            val d = sum.divide(BigDecimal.valueOf(dPeriod.toLong()), KisIndicatorMath.SCALE, RoundingMode.HALF_EVEN)
            out.add(IndicatorPoint(candles[i].ts, mapOf("k" to kv.norm(), "d" to d)))
        }
        return IndicatorResult(spec, out, SOURCE)
    }

    private fun computeStochasticK(
        candles: List<Candle>,
        period: Int,
    ): List<BigDecimal?> {
        val highMax = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        val lowMin = KisIndicatorMath.rollingMin(KisIndicatorMath.lows(candles), period)
        return candles.indices.map { i ->
            val h = highMax[i]
            val l = lowMin[i]
            if (h == null || l == null) {
                null
            } else {
                val denom = h.subtract(l, KisIndicatorMath.MC)
                if (denom.signum() == 0) {
                    BigDecimal.valueOf(50)
                } else {
                    candles[i]
                        .closeRaw
                        .subtract(l, KisIndicatorMath.MC)
                        .divide(denom, KisIndicatorMath.MC)
                        .multiply(HUNDRED, KisIndicatorMath.MC)
                }
            }
        }
    }

    /** calc_stochrsi indicators.py:792-801 */
    fun stochrsi(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val rsiPeriod = spec.intParam("rsi_period", 14)
        val stochPeriod = spec.intParam("stoch_period", 14)
        if (candles.size < rsiPeriod + stochPeriod) {
            throw IndicatorException.InsufficientCandlesException(rsiPeriod + stochPeriod, candles.size)
        }
        // 먼저 rsi 계산
        val rsiSpec = IndicatorSpec("rsi", mapOf("period" to rsiPeriod))
        val rsiResult = rsi(rsiSpec, candles).series
        val rsiByTime = rsiResult.associateBy { it.time }
        val rsiList = candles.map { c -> rsiByTime[c.ts]?.values?.get("rsi") }
        // rolling min/max over stochPeriod
        val out = ArrayList<IndicatorPoint>()
        for (i in candles.indices) {
            if (i < rsiPeriod + stochPeriod - 1) continue
            val window = rsiList.subList(i - stochPeriod + 1, i + 1).filterNotNull()
            if (window.size < stochPeriod) continue
            val mn = window.min()
            val mx = window.max()
            val cur = rsiList[i] ?: continue
            val denom = mx.subtract(mn, KisIndicatorMath.MC)
            val v =
                if (denom.signum() == 0) {
                    BigDecimal.valueOf(50)
                } else {
                    cur.subtract(mn, KisIndicatorMath.MC).divide(denom, KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                }
            out.add(IndicatorPoint(candles[i].ts, mapOf("stochrsi" to v.norm())))
        }
        return IndicatorResult(spec, out, SOURCE)
    }

    /** calc_cci indicators.py:561-589 */
    fun cci(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val tp =
            candles.map { c ->
                c.highRaw
                    .add(c.lowRaw)
                    .add(c.closeRaw, KisIndicatorMath.MC)
                    .divide(BigDecimal.valueOf(3), KisIndicatorMath.MC)
            }
        val tpSma = KisIndicatorMath.rollingMean(tp, period)
        val out = ArrayList<BigDecimal?>(candles.size)
        for (i in candles.indices) {
            if (i < period - 1) {
                out.add(null)
                continue
            }
            val window = tp.subList(i - period + 1, i + 1)
            val mean =
                window
                    .fold(BigDecimal.ZERO) { acc, v -> acc.add(v, KisIndicatorMath.MC) }
                    .divide(BigDecimal.valueOf(period.toLong()), KisIndicatorMath.MC)
            val md =
                window
                    .fold(BigDecimal.ZERO) { acc, v -> acc.add(v.subtract(mean, KisIndicatorMath.MC).abs(), KisIndicatorMath.MC) }
                    .divide(BigDecimal.valueOf(period.toLong()), KisIndicatorMath.MC)
            val sma = tpSma[i]!!
            val denom = md.multiply(BigDecimal.valueOf(0.015), KisIndicatorMath.MC)
            out.add(
                if (denom.signum() ==
                    0
                ) {
                    BigDecimal.ZERO
                } else {
                    tp[i].subtract(sma, KisIndicatorMath.MC).divide(denom, KisIndicatorMath.MC)
                },
            )
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "cci"), SOURCE)
    }

    /** calc_williams_r indicators.py:592-613 */
    fun williamsR(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val highMax = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        val lowMin = KisIndicatorMath.rollingMin(KisIndicatorMath.lows(candles), period)
        val out =
            candles.indices.map { i ->
                val h = highMax[i]
                val l = lowMin[i]
                if (h == null || l == null) {
                    null
                } else {
                    val denom = h.subtract(l, KisIndicatorMath.MC)
                    if (denom.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        h
                            .subtract(candles[i].closeRaw, KisIndicatorMath.MC)
                            .divide(denom, KisIndicatorMath.MC)
                            .multiply(BigDecimal.valueOf(-100), KisIndicatorMath.MC)
                    }
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "wr"), SOURCE)
    }

    /** calc_roc indicators.py:763-777 — pct_change(period) * 100 */
    fun roc(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 10)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val out =
            candles.indices.map { i ->
                if (i < period) {
                    null
                } else {
                    val prev = closes[i - period]
                    if (prev.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        closes[i]
                            .subtract(prev, KisIndicatorMath.MC)
                            .divide(prev, KisIndicatorMath.MC)
                            .multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "roc"), SOURCE)
    }

    /** calc_momentum indicators.py:785-789 */
    fun momentum(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 10)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val out =
            candles.indices.map { i ->
                if (i < period) null else closes[i].subtract(closes[i - period], KisIndicatorMath.MC)
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "momentum"), SOURCE)
    }

    /** calc_apo indicators.py:1124-1130 */
    fun apo(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val fast = spec.intParam("fast", 12)
        val slow = spec.intParam("slow", 26)
        if (candles.size < slow) throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val emaFast = KisIndicatorMath.ewm(closes, fast)
        val emaSlow = KisIndicatorMath.ewm(closes, slow)
        val series =
            closes.indices.map { i ->
                IndicatorPoint(candles[i].ts, mapOf("apo" to emaFast[i].subtract(emaSlow[i], KisIndicatorMath.MC).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_ppo indicators.py:1133-1139 */
    fun ppo(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val fast = spec.intParam("fast", 12)
        val slow = spec.intParam("slow", 26)
        if (candles.size < slow) throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val emaFast = KisIndicatorMath.ewm(closes, fast)
        val emaSlow = KisIndicatorMath.ewm(closes, slow)
        val series =
            closes.indices.map { i ->
                val v =
                    if (emaSlow[i].signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        emaFast[i]
                            .subtract(
                                emaSlow[i],
                                KisIndicatorMath.MC,
                            ).divide(emaSlow[i], KisIndicatorMath.MC)
                            .multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                IndicatorPoint(candles[i].ts, mapOf("ppo" to v.norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_cmo indicators.py:1142-1149 */
    fun cmo(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val diff = KisIndicatorMath.diff(closes)
        val gains = diff.map { if (it.signum() > 0) it else BigDecimal.ZERO }
        val losses = diff.map { if (it.signum() < 0) it.negate() else BigDecimal.ZERO }
        val gainSum = KisIndicatorMath.rollingSum(gains, period)
        val lossSum = KisIndicatorMath.rollingSum(losses, period)
        val out =
            candles.indices.map { i ->
                val g = gainSum[i]
                val l = lossSum[i]
                if (g == null || l == null) {
                    null
                } else {
                    val denom = g.add(l, KisIndicatorMath.MC)
                    if (denom.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        g.subtract(l, KisIndicatorMath.MC).divide(denom, KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "cmo"), SOURCE)
    }

    /** calc_ao indicators.py:1152-1157 — SMA(hl2,5) - SMA(hl2,34) */
    fun ao(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.size < 34) throw IndicatorException.InsufficientCandlesException(34, candles.size)
        val hl2 = candles.map { c -> c.highRaw.add(c.lowRaw, KisIndicatorMath.MC).divide(BigDecimal.valueOf(2), KisIndicatorMath.MC) }
        val sma5 = KisIndicatorMath.rollingMean(hl2, 5)
        val sma34 = KisIndicatorMath.rollingMean(hl2, 34)
        val out =
            candles.indices.map { i ->
                val s5 = sma5[i]
                val s34 = sma34[i]
                if (s5 == null || s34 == null) null else s5.subtract(s34, KisIndicatorMath.MC)
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "ao"), SOURCE)
    }

    /** calc_trix indicators.py:1184-1191 — triple EMA의 ROC */
    fun trix(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 15)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val e1 = KisIndicatorMath.ewm(closes, period)
        val e2 = KisIndicatorMath.ewm(e1, period)
        val e3 = KisIndicatorMath.ewm(e2, period)
        val out =
            candles.indices.map { i ->
                if (i < 1) {
                    null
                } else {
                    val prev = e3[i - 1]
                    if (prev.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        e3[i].subtract(prev, KisIndicatorMath.MC).divide(prev, KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "trix"), SOURCE)
    }

    /** calc_tsi indicators.py:1194-1201 */
    fun tsi(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val long = spec.intParam("long", 25)
        val short = spec.intParam("short", 13)
        if (candles.size < long) throw IndicatorException.InsufficientCandlesException(long, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val diff = KisIndicatorMath.diff(closes)
        val absDiff = diff.map { it.abs() }
        val s1 = KisIndicatorMath.ewm(KisIndicatorMath.ewm(diff, long), short)
        val s2 = KisIndicatorMath.ewm(KisIndicatorMath.ewm(absDiff, long), short)
        val series =
            closes.indices.map { i ->
                val v =
                    if (s2[i].signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        s1[i].divide(s2[i], KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                IndicatorPoint(candles[i].ts, mapOf("tsi" to v.norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_rvi indicators.py:1204-1212 */
    fun rvi(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 10)
        if (candles.size < period + 3) throw IndicatorException.InsufficientCandlesException(period + 3, candles.size)
        val co = candles.map { c -> c.closeRaw.subtract(c.openRaw, KisIndicatorMath.MC) }
        val hl = candles.map { c -> c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC) }
        val coShift1 = KisIndicatorMath.shift(co, 1)
        val coShift2 = KisIndicatorMath.shift(co, 2)
        val coShift3 = KisIndicatorMath.shift(co, 3)
        val hlShift1 = KisIndicatorMath.shift(hl, 1)
        val hlShift2 = KisIndicatorMath.shift(hl, 2)
        val hlShift3 = KisIndicatorMath.shift(hl, 3)
        val six = BigDecimal.valueOf(6)
        val numList =
            candles.indices.map { i ->
                if (i < 3) {
                    null
                } else {
                    co[i]
                        .add(coShift1[i]!!.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .add(coShift2[i]!!.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .add(coShift3[i]!!, KisIndicatorMath.MC)
                        .divide(six, KisIndicatorMath.MC)
                }
            }
        val denList =
            candles.indices.map { i ->
                if (i < 3) {
                    null
                } else {
                    hl[i]
                        .add(hlShift1[i]!!.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .add(hlShift2[i]!!.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .add(hlShift3[i]!!, KisIndicatorMath.MC)
                        .divide(six, KisIndicatorMath.MC)
                }
            }
        val numFilled = numList.map { it ?: BigDecimal.ZERO }
        val denFilled = denList.map { it ?: BigDecimal.ZERO }
        val numAvg = KisIndicatorMath.rollingMean(numFilled, period)
        val denAvg = KisIndicatorMath.rollingMean(denFilled, period)
        val out =
            candles.indices.map { i ->
                val n = numAvg[i]
                val d = denAvg[i]
                if (n == null || d == null || d.signum() == 0 || i < 3 + period - 1) {
                    null
                } else {
                    n.divide(d, KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "rvi"), SOURCE)
    }

    /** calc_dpo indicators.py:1215-1220 — close - SMA shifted */
    fun dpo(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val sma = KisIndicatorMath.rollingMean(closes, period)
        val shift = period / 2 + 1
        val out =
            candles.indices.map { i ->
                if (i - shift < 0) {
                    null
                } else {
                    val s = sma[i - shift] ?: return@map null
                    closes[i].subtract(s, KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "dpo"), SOURCE)
    }

    /** calc_kst indicators.py:1283-1294 */
    fun kst(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.size < 30) throw IndicatorException.InsufficientCandlesException(30, candles.size)
        val closes = KisIndicatorMath.closes(candles)

        fun pctChange(p: Int): List<BigDecimal?> =
            candles.indices.map { i ->
                if (i < p) {
                    null
                } else {
                    val prev = closes[i - p]
                    if (prev.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        closes[i]
                            .subtract(
                                prev,
                                KisIndicatorMath.MC,
                            ).divide(prev, KisIndicatorMath.MC)
                            .multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        val roc1 = pctChange(10)
        val roc2 = pctChange(15)
        val roc3 = pctChange(20)
        val roc4 = pctChange(30)

        fun smaOfNullable(
            values: List<BigDecimal?>,
            period: Int,
        ): List<BigDecimal?> {
            val out = ArrayList<BigDecimal?>(values.size)
            for (i in values.indices) {
                if (i < period - 1) {
                    out.add(null)
                    continue
                }
                var sum = BigDecimal.ZERO
                var ok = true
                for (j in i - period + 1..i) {
                    val v = values[j]
                    if (v == null) {
                        ok = false
                        break
                    }
                    sum = sum.add(v, KisIndicatorMath.MC)
                }
                out.add(if (ok) sum.divide(BigDecimal.valueOf(period.toLong()), KisIndicatorMath.MC) else null)
            }
            return out
        }
        val s1 = smaOfNullable(roc1, 10)
        val s2 = smaOfNullable(roc2, 10)
        val s3 = smaOfNullable(roc3, 10)
        val s4 = smaOfNullable(roc4, 15)
        val out =
            candles.indices.map { i ->
                val a = s1[i]
                val b = s2[i]
                val c = s3[i]
                val d = s4[i]
                if (a == null || b == null || c == null || d == null) {
                    null
                } else {
                    a
                        .add(b.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .add(c.multiply(BigDecimal.valueOf(3), KisIndicatorMath.MC), KisIndicatorMath.MC)
                        .add(d.multiply(BigDecimal.valueOf(4), KisIndicatorMath.MC), KisIndicatorMath.MC)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "kst"), SOURCE)
    }

    /** calc_coppock indicators.py:1297-1303 */
    fun coppock(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        if (candles.size < 14) throw IndicatorException.InsufficientCandlesException(14, candles.size)
        val closes = KisIndicatorMath.closes(candles)

        fun pctChange(p: Int): List<BigDecimal> =
            candles.indices.map { i ->
                if (i < p) {
                    BigDecimal.ZERO
                } else {
                    val prev = closes[i - p]
                    if (prev.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        closes[i]
                            .subtract(
                                prev,
                                KisIndicatorMath.MC,
                            ).divide(prev, KisIndicatorMath.MC)
                            .multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        val sum = pctChange(14).zip(pctChange(11)) { a, b -> a.add(b, KisIndicatorMath.MC) }
        val out = KisIndicatorMath.ewm(sum, 10)
        val series =
            out.mapIndexed { i, v ->
                IndicatorPoint(candles[i].ts, mapOf("coppock" to v.norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_schaff indicators.py:1317-1330 */
    fun schaff(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 23)
        val fast = spec.intParam("fast", 10)
        val slow = spec.intParam("slow", 50)
        if (candles.size < slow) throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val emaFast = KisIndicatorMath.ewm(closes, fast)
        val emaSlow = KisIndicatorMath.ewm(closes, slow)
        val macdLine = closes.indices.map { i -> emaFast[i].subtract(emaSlow[i], KisIndicatorMath.MC) }
        val ll = KisIndicatorMath.rollingMin(macdLine, period)
        val hh = KisIndicatorMath.rollingMax(macdLine, period)
        val stoch1 =
            candles.indices.map { i ->
                val l = ll[i]
                val h = hh[i]
                if (l == null || h == null) {
                    BigDecimal.ZERO
                } else {
                    val denom = h.subtract(l, KisIndicatorMath.MC)
                    if (denom.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        macdLine[i]
                            .subtract(
                                l,
                                KisIndicatorMath.MC,
                            ).divide(denom, KisIndicatorMath.MC)
                            .multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        val pf = KisIndicatorMath.ewmCom(stoch1, 1.0)
        val ll2 = KisIndicatorMath.rollingMin(pf, period)
        val hh2 = KisIndicatorMath.rollingMax(pf, period)
        val stoch2 =
            candles.indices.map { i ->
                val l = ll2[i]
                val h = hh2[i]
                if (l == null || h == null) {
                    BigDecimal.ZERO
                } else {
                    val denom = h.subtract(l, KisIndicatorMath.MC)
                    if (denom.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        pf[i].subtract(l, KisIndicatorMath.MC).divide(denom, KisIndicatorMath.MC).multiply(HUNDRED, KisIndicatorMath.MC)
                    }
                }
            }
        val schaff = KisIndicatorMath.ewmCom(stoch2, 1.0)
        val series = schaff.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("schaff" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_fisher indicators.py:1333-1345 */
    fun fisher(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 9)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val hl2 = candles.map { c -> c.highRaw.add(c.lowRaw, KisIndicatorMath.MC).divide(BigDecimal.valueOf(2), KisIndicatorMath.MC) }
        val highest = KisIndicatorMath.rollingMax(hl2, period)
        val lowest = KisIndicatorMath.rollingMin(hl2, period)
        val n = candles.size
        val fisher = DoubleArray(n)
        for (i in period until n) {
            val h = highest[i]
            val l = lowest[i]
            if (h == null || l == null) continue
            val denom = h.toDouble() - l.toDouble()
            val v = if (denom == 0.0) 0.0 else (2 * (hl2[i].toDouble() - l.toDouble()) / denom - 1).coerceIn(-0.999, 0.999)
            fisher[i] = 0.5 * ln((1 + v) / (1 - v)) * 0.5 + fisher[i - 1] * 0.5
        }
        val series =
            (period until n).map { i ->
                IndicatorPoint(candles[i].ts, mapOf("fisher" to BigDecimal.valueOf(fisher[i]).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }
}
