package kr.spdaewoon.magicjar.indicator.impl.kis

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.kis.KisIndicatorMath.norm
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * KIS Strategy Builder TREND 20지표 Kotlin port.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 *
 * 구현 함수: ma · ema · wma · hma · dema · tema · trima · lwma · t3 · zlema · kama · alma · frama · vidya ·
 *           macd · ichimoku_tenkan · ichimoku_kijun · supertrend · sar · regression_slope
 *
 * 모든 함수 KIS indicators.py 1:1 정합. 각 함수 KDoc에 raw 출처 줄번호 인용.
 */
internal object IndicatorTrend {
    private const val SOURCE = "kis-strategy-builder"

    /** calc_ma indicators.py:15-30 — `df[column].rolling(window=period).mean()` */
    fun ma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val sma = KisIndicatorMath.rollingMean(KisIndicatorMath.closes(candles), period)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, sma, "ma"), SOURCE)
    }

    /** calc_ema indicators.py:264-279 — `ewm(span=period, adjust=False).mean()` */
    fun ema(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val ema = KisIndicatorMath.ewm(KisIndicatorMath.closes(candles), period)
        val series = ema.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("ema" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_wma indicators.py:1074-1078 — `ewm(alpha=1.0/period, adjust=False).mean()` (Wilder 방식) */
    fun wma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val alpha = BigDecimal.ONE.divide(BigDecimal.valueOf(period.toLong()), KisIndicatorMath.MC)
        val w = KisIndicatorMath.ewmAlpha(KisIndicatorMath.closes(candles), alpha)
        val series = w.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("wma" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_hma indicators.py:952-962 — Hull MA: WMA(2*WMA(half) - WMA(period), sqrt(period)) (KIS는 SMA 사용) */
    fun hma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val half = period / 2
        val sqrtPeriod = sqrt(period.toDouble()).toInt().coerceAtLeast(1)
        val closes = KisIndicatorMath.closes(candles)
        val wmaHalf = KisIndicatorMath.rollingMean(closes, half)
        val wmaFull = KisIndicatorMath.rollingMean(closes, period)
        val diff =
            closes.indices.map { i ->
                val wh = wmaHalf[i] ?: return@map null
                val wf = wmaFull[i] ?: return@map null
                wh.multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC).subtract(wf, KisIndicatorMath.MC)
            }
        // SMA of diff (period = sqrtPeriod)
        val out = ArrayList<BigDecimal?>(closes.size)
        for (i in closes.indices) {
            if (i < period - 1 + sqrtPeriod - 1) {
                out.add(null)
            } else {
                var sum = BigDecimal.ZERO
                var ok = true
                for (j in i - sqrtPeriod + 1..i) {
                    val d = diff[j]
                    if (d == null) {
                        ok = false
                        break
                    }
                    sum = sum.add(d, KisIndicatorMath.MC)
                }
                out.add(
                    if (ok) sum.divide(BigDecimal.valueOf(sqrtPeriod.toLong()), KisIndicatorMath.SCALE, RoundingMode.HALF_EVEN) else null,
                )
            }
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "hma"), SOURCE)
    }

    /** calc_dema indicators.py:965-971 — 2*EMA - EMA(EMA) */
    fun dema(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val ema1 = KisIndicatorMath.ewm(KisIndicatorMath.closes(candles), period)
        val ema2 = KisIndicatorMath.ewm(ema1, period)
        val out =
            ema1.indices.map { i ->
                ema1[i].multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC).subtract(ema2[i], KisIndicatorMath.MC)
            }
        val series = out.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("dema" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_tema indicators.py:990-997 — 3*EMA - 3*EMA2 + EMA3 */
    fun tema(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val e1 = KisIndicatorMath.ewm(KisIndicatorMath.closes(candles), period)
        val e2 = KisIndicatorMath.ewm(e1, period)
        val e3 = KisIndicatorMath.ewm(e2, period)
        val three = BigDecimal.valueOf(3)
        val out =
            e1.indices.map { i ->
                three
                    .multiply(e1[i], KisIndicatorMath.MC)
                    .subtract(three.multiply(e2[i], KisIndicatorMath.MC), KisIndicatorMath.MC)
                    .add(e3[i], KisIndicatorMath.MC)
            }
        val series = out.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("tema" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_trima indicators.py:1037-1043 — SMA of SMA */
    fun trima(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val half = (period + 1) / 2
        val closes = KisIndicatorMath.closes(candles)
        val sma1 = KisIndicatorMath.rollingMean(closes, half)
        // sma1의 SMA. null 항은 그대로 propagate.
        val out = ArrayList<BigDecimal?>(closes.size)
        for (i in closes.indices) {
            if (i < half - 1 + half - 1) {
                out.add(null)
            } else {
                var sum = BigDecimal.ZERO
                var ok = true
                for (j in i - half + 1..i) {
                    val v = sma1[j]
                    if (v == null) {
                        ok = false
                        break
                    }
                    sum = sum.add(v, KisIndicatorMath.MC)
                }
                out.add(if (ok) sum.divide(BigDecimal.valueOf(half.toLong()), KisIndicatorMath.SCALE, RoundingMode.HALF_EVEN) else null)
            }
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "trima"), SOURCE)
    }

    /** calc_lwma indicators.py:1029-1034 — Linear Weighted MA (1..period weights) */
    fun lwma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val weightSum = (1..period).sum().toLong()
        val ws = BigDecimal.valueOf(weightSum)
        val out = ArrayList<BigDecimal?>(closes.size)
        for (i in closes.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                var num = BigDecimal.ZERO
                for (k in 0 until period) {
                    val w = BigDecimal.valueOf((k + 1).toLong())
                    num = num.add(w.multiply(closes[i - period + 1 + k], KisIndicatorMath.MC), KisIndicatorMath.MC)
                }
                out.add(num.divide(ws, KisIndicatorMath.SCALE, RoundingMode.HALF_EVEN))
            }
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "lwma"), SOURCE)
    }

    /** calc_t3 indicators.py:1046-1062 — Tillson T3 */
    fun t3(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        val v = spec.bdParam("volume_factor", BigDecimal.valueOf(0.7)).toDouble()
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val c1 = -(v * v * v)
        val c2 = 3 * v * v + 3 * v * v * v
        val c3 = -6 * v * v - 3 * v - 3 * v * v * v
        val c4 = 1 + 3 * v + v * v * v + 3 * v * v
        val closes = KisIndicatorMath.closes(candles)
        val e1 = KisIndicatorMath.ewm(closes, period)
        val e2 = KisIndicatorMath.ewm(e1, period)
        val e3 = KisIndicatorMath.ewm(e2, period)
        val e4 = KisIndicatorMath.ewm(e3, period)
        val e5 = KisIndicatorMath.ewm(e4, period)
        val e6 = KisIndicatorMath.ewm(e5, period)
        val series =
            closes.indices.map { i ->
                val res = c1 * e6[i].toDouble() + c2 * e5[i].toDouble() + c3 * e4[i].toDouble() + c4 * e3[i].toDouble()
                IndicatorPoint(candles[i].ts, mapOf("t3" to BigDecimal.valueOf(res).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_zlema indicators.py:1065-1071 — EMA on (2*close - close.shift(lag)) where lag=(period-1)/2 */
    fun zlema(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val lag = (period - 1) / 2
        val closes = KisIndicatorMath.closes(candles)
        val shifted = KisIndicatorMath.shift(closes, lag)
        val adjusted =
            closes.indices.map { i ->
                val s = shifted[i] ?: return@map closes[i] // pandas NaN→shift propagation; before lag → 본 결과는 무시되도록 채워둠
                closes[i].multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC).subtract(s, KisIndicatorMath.MC)
            }
        val out = KisIndicatorMath.ewm(adjusted, period)
        val series =
            out
                .mapIndexed { i, v ->
                    val time = candles[i].ts
                    if (i < lag) {
                        IndicatorPoint(time, emptyMap())
                    } else {
                        IndicatorPoint(time, mapOf("zlema" to v.norm()))
                    }
                }.filter { it.values.isNotEmpty() }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_kama indicators.py:1000-1014 — Kaufman Adaptive MA */
    fun kama(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val n = closes.size
        val direction =
            DoubleArray(n) { i ->
                if (i <
                    period
                ) {
                    0.0
                } else {
                    (closes[i].toDouble() - closes[i - period].toDouble()).let { if (it < 0) -it else it }
                }
            }
        val volatility = DoubleArray(n)
        for (i in closes.indices) {
            if (i < period) {
                volatility[i] = 0.0
                continue
            }
            var s = 0.0
            for (j in i - period + 1..i) s += kotlin.math.abs(closes[j].toDouble() - closes[j - 1].toDouble())
            volatility[i] = s
        }
        val fastSc = 2.0 / 3.0
        val slowSc = 2.0 / 31.0
        val kama = DoubleArray(n) { Double.NaN }
        kama[period - 1] = closes[period - 1].toDouble()
        for (i in period until n) {
            val er = if (volatility[i] == 0.0) Double.NaN else direction[i] / volatility[i]
            val sc = if (er.isNaN()) 0.0 else (er * (fastSc - slowSc) + slowSc).let { it * it }
            kama[i] = kama[i - 1] + sc * (closes[i].toDouble() - kama[i - 1])
        }
        val series =
            kama.indices.mapNotNull { i ->
                if (kama[i].isNaN()) {
                    null
                } else {
                    IndicatorPoint(candles[i].ts, mapOf("kama" to BigDecimal.valueOf(kama[i]).norm()))
                }
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_alma indicators.py:1017-1026 — Arnaud Legoux MA */
    fun alma(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        val sigma = spec.bdParam("sigma", BigDecimal.valueOf(6.0)).toDouble()
        val offset = spec.bdParam("offset", BigDecimal.valueOf(0.85)).toDouble()
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val m = offset * (period - 1)
        val s = period / sigma
        val w = DoubleArray(period) { i -> exp(-((i - m) * (i - m)) / (2 * s * s)) }
        val wSum = w.sum()
        for (i in w.indices) w[i] /= wSum
        val closes = KisIndicatorMath.closes(candles)
        val out = ArrayList<BigDecimal?>(closes.size)
        for (i in closes.indices) {
            if (i < period - 1) {
                out.add(null)
                continue
            }
            var v = 0.0
            for (k in 0 until period) v += closes[i - period + 1 + k].toDouble() * w[k]
            out.add(BigDecimal.valueOf(v))
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "alma"), SOURCE)
    }

    /** calc_frama indicators.py:1081-1104 — Fractal Adaptive MA (KIS 근사 구현) */
    fun frama(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles).map { it.toDouble() }
        val n = closes.size
        val half = period / 2

        fun rollMax(
            arr: List<Double>,
            p: Int,
            end: Int,
        ): Double {
            if (end - p + 1 < 0) return Double.NaN
            var mx = arr[end - p + 1]
            for (j in end - p + 2..end) if (arr[j] > mx) mx = arr[j]
            return mx
        }

        fun rollMin(
            arr: List<Double>,
            p: Int,
            end: Int,
        ): Double {
            if (end - p + 1 < 0) return Double.NaN
            var mn = arr[end - p + 1]
            for (j in end - p + 2..end) if (arr[j] < mn) mn = arr[j]
            return mn
        }
        val frama = DoubleArray(n) { Double.NaN }
        val start = period - 1
        if (start < n) frama[start] = closes[start]
        for (i in start + 1 until n) {
            val n1 = (rollMax(closes, half, i) - rollMin(closes, half, i)) / half
            val n2 = if (i - half >= half - 1) (rollMax(closes, half, i - half) - rollMin(closes, half, i - half)) / half else Double.NaN
            val n3 = (rollMax(closes, period, i) - rollMin(closes, period, i)) / period
            val a =
                if (n1.isNaN() || n2.isNaN() || n3.isNaN() || n1 + n2 <= 0 || n3 <= 0) {
                    0.5
                } else {
                    val d = (ln(n1 + n2) - ln(n3)) / ln(2.0)
                    exp(-4.6 * (d - 1)).coerceIn(0.01, 1.0)
                }
            frama[i] = a * closes[i] + (1 - a) * frama[i - 1]
        }
        val series =
            frama.indices.mapNotNull { i ->
                if (frama[i].isNaN()) {
                    null
                } else {
                    IndicatorPoint(candles[i].ts, mapOf("frama" to BigDecimal.valueOf(frama[i]).norm()))
                }
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_vidya indicators.py:1107-1119 — Variable Index Dynamic Average (CMO 사용) */
    fun vidya(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        // CMO: rolling sum gain/loss
        val diffs = KisIndicatorMath.diff(closes)
        val gains = diffs.map { if (it.signum() > 0) it else BigDecimal.ZERO }
        val losses = diffs.map { if (it.signum() < 0) it.negate() else BigDecimal.ZERO }
        val gainSum = KisIndicatorMath.rollingSum(gains, period)
        val lossSum = KisIndicatorMath.rollingSum(losses, period)
        val n = closes.size
        val sc = 2.0 / (period + 1)
        val vidya = DoubleArray(n) { Double.NaN }
        if (period - 1 < n) vidya[period - 1] = closes[period - 1].toDouble()
        for (i in period until n) {
            val g = gainSum[i]
            val l = lossSum[i]
            val cmoAbs =
                if (g != null && l != null && (g.add(l)).signum() != 0) {
                    kotlin.math.abs((g.subtract(l)).divide(g.add(l), KisIndicatorMath.MC).toDouble()) // 0..1
                } else {
                    0.0
                }
            val k = sc * cmoAbs
            vidya[i] = k * closes[i].toDouble() + (1 - k) * vidya[i - 1]
        }
        val series =
            vidya.indices.mapNotNull { i ->
                if (vidya[i].isNaN()) {
                    null
                } else {
                    IndicatorPoint(candles[i].ts, mapOf("vidya" to BigDecimal.valueOf(vidya[i]).norm()))
                }
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_macd + signal + histogram indicators.py:312-389 (MACD only — single output {macd, signal, histogram}) */
    fun macd(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val fast = spec.intParam("fast", 12)
        val slow = spec.intParam("slow", 26)
        val signalPeriod = spec.intParam("signal", 9)
        if (candles.size < slow) throw IndicatorException.InsufficientCandlesException(slow, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val emaFast = KisIndicatorMath.ewm(closes, fast)
        val emaSlow = KisIndicatorMath.ewm(closes, slow)
        val macd = emaFast.zip(emaSlow) { a, b -> a.subtract(b, KisIndicatorMath.MC) }
        val signal = KisIndicatorMath.ewm(macd, signalPeriod)
        val series =
            closes.indices.map { i ->
                val histogram = macd[i].subtract(signal[i], KisIndicatorMath.MC).norm()
                IndicatorPoint(
                    candles[i].ts,
                    mapOf(
                        "macd" to macd[i].norm(),
                        "signal" to signal[i].norm(),
                        "histogram" to histogram,
                    ),
                )
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_ichimoku_tenkan indicators.py:938-942 — (high.max + low.min) / 2 */
    fun ichimokuTenkan(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 9)
        return ichimokuLine(spec, candles, period, "tenkan")
    }

    /** calc_ichimoku_kijun indicators.py:945-949 */
    fun ichimokuKijun(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 26)
        return ichimokuLine(spec, candles, period, "kijun")
    }

    private fun ichimokuLine(
        spec: IndicatorSpec,
        candles: List<Candle>,
        period: Int,
        key: String,
    ): IndicatorResult {
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val highMax = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        val lowMin = KisIndicatorMath.rollingMin(KisIndicatorMath.lows(candles), period)
        val out =
            highMax.indices.map { i ->
                val h = highMax[i]
                val l = lowMin[i]
                if (h == null || l == null) {
                    null
                } else {
                    h.add(l, KisIndicatorMath.MC).divide(BigDecimal.valueOf(2), KisIndicatorMath.SCALE, RoundingMode.HALF_EVEN)
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, key), SOURCE)
    }

    /** calc_supertrend indicators.py:872-893 — direction (1=up, -1=down). values key: value+direction. */
    fun supertrend(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        val multiplier = spec.bdParam("multiplier", BigDecimal.valueOf(3.0))
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val tr = KisIndicatorMath.trueRange(candles)
        val atr = KisIndicatorMath.ewm(tr, period)
        val n = candles.size
        val direction = DoubleArray(n) { 1.0 }
        val upper = DoubleArray(n)
        val lower = DoubleArray(n)
        val mDouble = multiplier.toDouble()
        for (i in 0 until n) {
            val hl2 = (candles[i].highRaw.toDouble() + candles[i].lowRaw.toDouble()) / 2.0
            val a = atr[i].toDouble()
            upper[i] = hl2 + mDouble * a
            lower[i] = hl2 - mDouble * a
        }
        for (i in 1 until n) {
            val close = candles[i].closeRaw.toDouble()
            direction[i] =
                when {
                    close > upper[i - 1] -> 1.0
                    close < lower[i - 1] -> -1.0
                    else -> direction[i - 1]
                }
        }
        val series =
            candles.indices.map { i ->
                val v = if (direction[i] >= 0) upper[i] else lower[i]
                IndicatorPoint(
                    candles[i].ts,
                    mapOf(
                        "value" to BigDecimal.valueOf(v).norm(),
                        "direction" to BigDecimal.valueOf(direction[i]).norm(),
                    ),
                )
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_sar indicators.py:896-935 — Parabolic SAR */
    fun sar(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val afStart = spec.bdParam("af_start", BigDecimal.valueOf(0.02)).toDouble()
        val afMax = spec.bdParam("af_max", BigDecimal.valueOf(0.2)).toDouble()
        if (candles.size < 2) throw IndicatorException.InsufficientCandlesException(2, candles.size)
        val n = candles.size
        val high = candles.map { it.highRaw.toDouble() }
        val low = candles.map { it.lowRaw.toDouble() }
        val sar = DoubleArray(n)
        var trend = 1
        var ep = high[0]
        var af = afStart
        sar[0] = low[0]
        for (i in 1 until n) {
            sar[i] = sar[i - 1] + af * (ep - sar[i - 1])
            if (trend == 1) {
                if (low[i] < sar[i]) {
                    trend = -1
                    sar[i] = ep
                    ep = low[i]
                    af = afStart
                } else if (high[i] > ep) {
                    ep = high[i]
                    af = kotlin.math.min(af + afStart, afMax)
                }
            } else {
                if (high[i] > sar[i]) {
                    trend = 1
                    sar[i] = ep
                    ep = high[i]
                    af = afStart
                } else if (low[i] < ep) {
                    ep = low[i]
                    af = kotlin.math.min(af + afStart, afMax)
                }
            }
        }
        val series =
            sar.indices.map { i ->
                IndicatorPoint(candles[i].ts, mapOf("sar" to BigDecimal.valueOf(sar[i]).norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_regression_slope indicators.py:1469-1478 */
    fun regressionSlope(
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
            out.add(BigDecimal.valueOf(num / xVar))
        }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "slope"), SOURCE)
    }
}
