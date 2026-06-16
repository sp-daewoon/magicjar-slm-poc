package kr.spdaewoon.magicjar.indicator.impl.kis

import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * KIS Strategy Builder 80지표용 공통 산술 helper.
 *
 * KIS Python pandas / numpy 기능을 Kotlin BigDecimal·Double 혼합으로 port.
 * - 단순 통계(rolling mean·std·sum·max·min)는 BigDecimal로 정밀 계산
 * - 복합 함수(log·exp·sqrt 등) 또는 정밀도 영향 작은 부분은 Double 사용 후 BigDecimal 변환
 *
 * Source: indicators.py (보조 유틸 calc_returns 등)
 * License: MIT
 */
internal object KisIndicatorMath {
    val MC: MathContext = MathContext(20, RoundingMode.HALF_EVEN)
    const val SCALE: Int = 8

    fun closes(candles: List<Candle>): List<BigDecimal> = candles.map { it.closeRaw }

    fun highs(candles: List<Candle>): List<BigDecimal> = candles.map { it.highRaw }

    fun lows(candles: List<Candle>): List<BigDecimal> = candles.map { it.lowRaw }

    fun opens(candles: List<Candle>): List<BigDecimal> = candles.map { it.openRaw }

    fun volumes(candles: List<Candle>): List<Long> = candles.map { it.volume }

    /**
     * pandas `Series.diff()` — index 0은 0으로 처리 (KIS 함수들은 NaN을 후속 연산에서 처리하므로 의미 동일).
     */
    fun diff(values: List<BigDecimal>): List<BigDecimal> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<BigDecimal>(values.size)
        out.add(BigDecimal.ZERO)
        for (i in 1 until values.size) out.add(values[i].subtract(values[i - 1], MC))
        return out
    }

    /**
     * pandas `ewm(span=period, adjust=False).mean()`.
     * 결과 size = values.size, 첫 값 = values[0].
     */
    fun ewm(
        values: List<BigDecimal>,
        span: Int,
    ): List<BigDecimal> {
        if (values.isEmpty()) return emptyList()
        val alpha = BigDecimal.valueOf(2.0).divide(BigDecimal.valueOf((span + 1).toLong()), MC)
        val one = BigDecimal.ONE
        val out = ArrayList<BigDecimal>(values.size)
        var prev = values[0]
        out.add(prev)
        for (i in 1 until values.size) {
            val next =
                alpha
                    .multiply(values[i], MC)
                    .add(one.subtract(alpha, MC).multiply(prev, MC), MC)
            prev = next
            out.add(next)
        }
        return out
    }

    /**
     * pandas `ewm(alpha=alpha, adjust=False).mean()` — Wilder smoothing 등에 사용.
     */
    fun ewmAlpha(
        values: List<BigDecimal>,
        alpha: BigDecimal,
    ): List<BigDecimal> {
        if (values.isEmpty()) return emptyList()
        val one = BigDecimal.ONE
        val out = ArrayList<BigDecimal>(values.size)
        var prev = values[0]
        out.add(prev)
        for (i in 1 until values.size) {
            val next =
                alpha
                    .multiply(values[i], MC)
                    .add(one.subtract(alpha, MC).multiply(prev, MC), MC)
            prev = next
            out.add(next)
        }
        return out
    }

    /**
     * pandas `Series.ewm(com=com, adjust=False).mean()` — α = 1/(1+com).
     */
    fun ewmCom(
        values: List<BigDecimal>,
        com: Double,
    ): List<BigDecimal> {
        val alpha = BigDecimal.valueOf(1.0 / (1.0 + com))
        return ewmAlpha(values, alpha)
    }

    /**
     * pandas `rolling(window=period).mean()`. NaN 자리는 null 로 표현.
     */
    fun rollingMean(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = ArrayList<BigDecimal?>(values.size)
        for (i in values.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                var sum = BigDecimal.ZERO
                for (j in i - period + 1..i) sum = sum.add(values[j], MC)
                out.add(sum.divide(BigDecimal.valueOf(period.toLong()), SCALE, RoundingMode.HALF_EVEN))
            }
        }
        return out
    }

    fun rollingSum(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = ArrayList<BigDecimal?>(values.size)
        for (i in values.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                var sum = BigDecimal.ZERO
                for (j in i - period + 1..i) sum = sum.add(values[j], MC)
                out.add(sum)
            }
        }
        return out
    }

    fun rollingMax(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = ArrayList<BigDecimal?>(values.size)
        for (i in values.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                var m = values[i - period + 1]
                for (j in i - period + 2..i) if (values[j] > m) m = values[j]
                out.add(m)
            }
        }
        return out
    }

    fun rollingMin(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = ArrayList<BigDecimal?>(values.size)
        for (i in values.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                var m = values[i - period + 1]
                for (j in i - period + 2..i) if (values[j] < m) m = values[j]
                out.add(m)
            }
        }
        return out
    }

    /**
     * pandas `rolling(window=period).std()` — sample std (ddof=1).
     */
    fun rollingStd(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = ArrayList<BigDecimal?>(values.size)
        for (i in values.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                val window = values.subList(i - period + 1, i + 1)
                val mean =
                    window
                        .fold(BigDecimal.ZERO) { acc, v -> acc.add(v, MC) }
                        .divide(BigDecimal.valueOf(period.toLong()), MC)
                val variance =
                    window
                        .fold(BigDecimal.ZERO) { acc, v ->
                            val d = v.subtract(mean, MC)
                            acc.add(d.multiply(d, MC), MC)
                        }.divide(BigDecimal.valueOf((period - 1).toLong()), MC)
                val sd =
                    BigDecimal
                        .valueOf(kotlin.math.sqrt(variance.toDouble()))
                        .setScale(SCALE, RoundingMode.HALF_EVEN)
                out.add(sd)
            }
        }
        return out
    }

    /**
     * pandas `rolling(window=period).var()` — sample variance (ddof=1).
     */
    fun rollingVariance(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = ArrayList<BigDecimal?>(values.size)
        for (i in values.indices) {
            if (i < period - 1) {
                out.add(null)
            } else {
                val window = values.subList(i - period + 1, i + 1)
                val mean =
                    window
                        .fold(BigDecimal.ZERO) { acc, v -> acc.add(v, MC) }
                        .divide(BigDecimal.valueOf(period.toLong()), MC)
                val variance =
                    window
                        .fold(BigDecimal.ZERO) { acc, v ->
                            val d = v.subtract(mean, MC)
                            acc.add(d.multiply(d, MC), MC)
                        }.divide(BigDecimal.valueOf((period - 1).toLong()), SCALE, RoundingMode.HALF_EVEN)
                out.add(variance)
            }
        }
        return out
    }

    /**
     * 단일 series → IndicatorPoint list 변환.
     */
    fun toSeries(
        candles: List<Candle>,
        values: List<BigDecimal?>,
        key: String,
    ): List<IndicatorPoint> {
        val out = ArrayList<IndicatorPoint>(values.size)
        for (i in values.indices) {
            val v = values[i] ?: continue
            out.add(IndicatorPoint(time = candles[i].ts, values = mapOf(key to v.setScale(SCALE, RoundingMode.HALF_EVEN))))
        }
        return out
    }

    /**
     * KIS 결과 BigDecimal 정밀도 일관 normalize.
     */
    fun BigDecimal.norm(): BigDecimal = setScale(SCALE, RoundingMode.HALF_EVEN)

    /**
     * 안전 나눗셈 — 0 또는 null → null.
     */
    fun safeDiv(
        a: BigDecimal?,
        b: BigDecimal?,
    ): BigDecimal? {
        if (a == null || b == null || b.signum() == 0) return null
        return a.divide(b, MC)
    }

    /**
     * pandas `Series.shift(n)` — 첫 n 항은 null.
     */
    fun <T> shift(
        values: List<T>,
        n: Int,
    ): List<T?> {
        val out = ArrayList<T?>(values.size)
        for (i in values.indices) {
            if (i < n) out.add(null) else out.add(values[i - n])
        }
        return out
    }

    /**
     * True Range — pandas `concat([high-low, |high - close.shift(1)|, |low - close.shift(1)|]).max(axis=1)`.
     */
    fun trueRange(candles: List<Candle>): List<BigDecimal> {
        val out = ArrayList<BigDecimal>(candles.size)
        if (candles.isEmpty()) return out
        // 첫 값은 high-low (close.shift(1) 부재)
        val c0 = candles[0]
        out.add(c0.highRaw.subtract(c0.lowRaw, MC))
        for (i in 1 until candles.size) {
            val h = candles[i].highRaw
            val l = candles[i].lowRaw
            val pc = candles[i - 1].closeRaw
            val tr1 = h.subtract(l, MC)
            val tr2 = h.subtract(pc, MC).abs()
            val tr3 = l.subtract(pc, MC).abs()
            val max = listOf(tr1, tr2, tr3).max()
            out.add(max)
        }
        return out
    }
}
