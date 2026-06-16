package kr.spdaewoon.magicjar.indicator.impl.native

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Native 5종 helper용 산술 유틸.
 *
 * 정밀도 정책 (spec §8-3 LOCK):
 *   - scale = 8 (소수점 8자리)
 *   - RoundingMode.HALF_EVEN (banker's rounding)
 *   - 정확성 fixture drift < 0.001% 충족
 *
 * KIS Python pandas calc_xxx 와 1:1 cross-check 정합.
 */
internal object IndicatorMath {
    val MC: MathContext = MathContext(20, RoundingMode.HALF_EVEN)
    const val SCALE: Int = 8

    /**
     * EMA recursion (KIS pandas `ewm(span=period, adjust=False).mean()` 정합).
     *
     * α = 2 / (span + 1), EMA_t = α * x_t + (1 - α) * EMA_{t-1}, EMA_0 = x_0.
     *
     * 결과 길이 == values.size (warm-up 별도 처리 안 함 — KIS와 동일 동작).
     */
    fun ema(
        values: List<BigDecimal>,
        span: Int,
    ): List<BigDecimal> {
        if (values.isEmpty()) return emptyList()
        val alpha = BigDecimal.valueOf(2.0).divide(BigDecimal.valueOf((span + 1).toLong()), MC)
        val oneMinusAlpha = BigDecimal.ONE.subtract(alpha, MC)
        val out = ArrayList<BigDecimal>(values.size)
        var prev = values[0]
        out.add(prev.setScale(SCALE, RoundingMode.HALF_EVEN))
        for (i in 1 until values.size) {
            val next = alpha.multiply(values[i], MC).add(oneMinusAlpha.multiply(prev, MC), MC)
            prev = next
            out.add(next.setScale(SCALE, RoundingMode.HALF_EVEN))
        }
        return out
    }

    /**
     * Simple Moving Average (KIS pandas `rolling(window=period).mean()` 정합).
     *
     * 결과: 첫 (period - 1) 개는 null 또는 NaN — 본 구현은 size = values.size, NaN 자리는 null로 표현하지 않고
     * 호출 측에서 `series.drop(period - 1)` 처리. drop list 크기 = values.size - (period - 1).
     *
     * @return List<BigDecimal> — 크기 = values.size - period + 1 (warm-up 제거됨)
     */
    fun smaTrimmed(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal> {
        if (values.size < period) return emptyList()
        val out = ArrayList<BigDecimal>(values.size - period + 1)
        var sum = BigDecimal.ZERO
        for (i in 0 until period) sum = sum.add(values[i], MC)
        val periodBd = BigDecimal.valueOf(period.toLong())
        out.add(sum.divide(periodBd, SCALE, RoundingMode.HALF_EVEN))
        for (i in period until values.size) {
            sum = sum.add(values[i], MC).subtract(values[i - period], MC)
            out.add(sum.divide(periodBd, SCALE, RoundingMode.HALF_EVEN))
        }
        return out
    }

    /**
     * Sample standard deviation — pandas `rolling(period).std()` 정합 (ddof=1).
     *
     * @return List<BigDecimal> — 크기 = values.size - period + 1
     */
    fun rollingStdSample(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal> {
        if (values.size < period) return emptyList()
        val out = ArrayList<BigDecimal>(values.size - period + 1)
        for (end in period - 1 until values.size) {
            val window = values.subList(end - period + 1, end + 1)
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
                variance
                    .toDouble()
                    .let { BigDecimal.valueOf(kotlin.math.sqrt(it)) }
                    .setScale(SCALE, RoundingMode.HALF_EVEN)
            out.add(sd)
        }
        return out
    }

    fun closes(
        candles: List<kr.spdaewoon.magicjar.domain.market.candle.Candle>,
        adjusted: Boolean = false,
    ): List<BigDecimal> =
        candles.map { c ->
            if (adjusted) c.closeAdj ?: c.closeRaw else c.closeRaw
        }
}
