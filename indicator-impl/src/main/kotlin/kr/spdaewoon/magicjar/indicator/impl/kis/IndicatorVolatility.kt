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
 * KIS Strategy Builder VOLATILITY 12지표 Kotlin port.
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 */
internal object IndicatorVolatility {
    private const val SOURCE = "kis-strategy-builder"

    /** calc_bb indicators.py:392-466 — middle/upper/lower 3-line */
    fun bb(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        val stdDev = spec.bdParam("std_dev", BigDecimal.valueOf(2.0))
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val middle = KisIndicatorMath.rollingMean(closes, period)
        val std = KisIndicatorMath.rollingStd(closes, period)
        val series =
            candles.indices.mapNotNull { i ->
                val m = middle[i]
                val s = std[i]
                if (m == null || s == null) {
                    null
                } else {
                    val band = s.multiply(stdDev, KisIndicatorMath.MC)
                    IndicatorPoint(
                        candles[i].ts,
                        mapOf(
                            "upper" to m.add(band, KisIndicatorMath.MC).norm(),
                            "middle" to m.norm(),
                            "lower" to m.subtract(band, KisIndicatorMath.MC).norm(),
                        ),
                    )
                }
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_bb_width indicators.py:448-466 — upper - lower */
    fun bbWidth(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        val stdDev = spec.bdParam("std_dev", BigDecimal.valueOf(2.0))
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val std = KisIndicatorMath.rollingStd(closes, period)
        val series =
            candles.indices.mapNotNull { i ->
                val s = std[i] ?: return@mapNotNull null
                val width = s.multiply(stdDev, KisIndicatorMath.MC).multiply(BigDecimal.valueOf(2), KisIndicatorMath.MC).norm()
                IndicatorPoint(candles[i].ts, mapOf("width" to width))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_bb_percent indicators.py:469-487 — (close - lower) / (upper - lower) */
    fun bbPercent(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        val stdDev = spec.bdParam("std_dev", BigDecimal.valueOf(2.0))
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val middle = KisIndicatorMath.rollingMean(closes, period)
        val std = KisIndicatorMath.rollingStd(closes, period)
        val series =
            candles.indices.mapNotNull { i ->
                val m = middle[i]
                val s = std[i]
                if (m == null || s == null) {
                    null
                } else {
                    val band = s.multiply(stdDev, KisIndicatorMath.MC)
                    val upper = m.add(band, KisIndicatorMath.MC)
                    val lower = m.subtract(band, KisIndicatorMath.MC)
                    val denom = upper.subtract(lower, KisIndicatorMath.MC)
                    val pct =
                        if (denom.signum() == 0) {
                            BigDecimal.ZERO
                        } else {
                            closes[i].subtract(lower, KisIndicatorMath.MC).divide(denom, KisIndicatorMath.MC)
                        }
                    IndicatorPoint(candles[i].ts, mapOf("percent" to pct.norm()))
                }
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_atr indicators.py:532-558 — TR의 EMA */
    fun atr(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val tr = KisIndicatorMath.trueRange(candles)
        val atr = KisIndicatorMath.ewm(tr, period)
        val series = atr.mapIndexed { i, v -> IndicatorPoint(candles[i].ts, mapOf("atr" to v.norm())) }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_natr indicators.py:828-833 — ATR / Close * 100 */
    fun natr(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val tr = KisIndicatorMath.trueRange(candles)
        val atr = KisIndicatorMath.ewm(tr, period)
        val series =
            candles.indices.map { i ->
                val close = candles[i].closeRaw
                val v =
                    if (close.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        atr[i].divide(close, KisIndicatorMath.MC).multiply(BigDecimal.valueOf(100), KisIndicatorMath.MC)
                    }
                IndicatorPoint(candles[i].ts, mapOf("natr" to v.norm()))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_keltner_upper indicators.py:836-844 */
    fun keltnerUpper(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val emaPeriod = spec.intParam("ema_period", 20)
        val atrPeriod = spec.intParam("atr_period", 14)
        val multiplier = spec.bdParam("multiplier", BigDecimal.valueOf(2.0))
        if (candles.size < kotlin.math.max(emaPeriod, atrPeriod + 1)) {
            throw IndicatorException.InsufficientCandlesException(kotlin.math.max(emaPeriod, atrPeriod + 1), candles.size)
        }
        val ema = KisIndicatorMath.ewm(KisIndicatorMath.closes(candles), emaPeriod)
        val tr = KisIndicatorMath.trueRange(candles)
        val atr = KisIndicatorMath.ewm(tr, atrPeriod)
        val series =
            candles.indices.map { i ->
                val v = ema[i].add(atr[i].multiply(multiplier, KisIndicatorMath.MC), KisIndicatorMath.MC).norm()
                IndicatorPoint(candles[i].ts, mapOf("upper" to v))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /** calc_keltner_lower indicators.py:847-855 */
    fun keltnerLower(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val emaPeriod = spec.intParam("ema_period", 20)
        val atrPeriod = spec.intParam("atr_period", 14)
        val multiplier = spec.bdParam("multiplier", BigDecimal.valueOf(2.0))
        if (candles.size < kotlin.math.max(emaPeriod, atrPeriod + 1)) {
            throw IndicatorException.InsufficientCandlesException(kotlin.math.max(emaPeriod, atrPeriod + 1), candles.size)
        }
        val ema = KisIndicatorMath.ewm(KisIndicatorMath.closes(candles), emaPeriod)
        val tr = KisIndicatorMath.trueRange(candles)
        val atr = KisIndicatorMath.ewm(tr, atrPeriod)
        val series =
            candles.indices.map { i ->
                val v = ema[i].subtract(atr[i].multiply(multiplier, KisIndicatorMath.MC), KisIndicatorMath.MC).norm()
                IndicatorPoint(candles[i].ts, mapOf("lower" to v))
            }
        return IndicatorResult(spec, series, SOURCE)
    }

    /**
     * 돈치안 채널 상단 — **직전 N봉(현재 봉 제외)의 최고가**.
     *
     * 표준 Donchian breakout 정의: `donchian_upper(n)[i] = max(high[i-n .. i-1])`.
     * 현재 봉을 포함하면 `high[i]`가 max에 들어가 `close[i] > donchian_upper(n)`이
     * 항상 false가 되어(close ≤ high ≤ max) 돌파 비교가 무력화된다(V76 검증 #860 실측).
     * `rollingMax`(현재 봉 포함) 결과를 1봉 shift 하여 현재 봉을 제외한다.
     * 현재 봉 제외 = 미래 미참조이므로 룩어헤드 가드와도 정합.
     */
    fun donchianUpper(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        // 직전 N봉을 보려면 (현재 봉 제외) 캔들이 N+1개 이상 필요.
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val highMax = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        // 1봉 shift → donchian_upper(n)[i] = rollingMax[i-1] = max(high[i-n .. i-1]) (현재 봉 제외).
        val priorMax: List<BigDecimal?> = KisIndicatorMath.shift(highMax, 1)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, priorMax, "upper"), SOURCE)
    }

    /**
     * 돈치안 채널 하단 — **직전 N봉(현재 봉 제외)의 최저가**.
     *
     * 표준 정의 정합: `donchian_lower(n)[i] = min(low[i-n .. i-1])`. donchianUpper 동일 사유로
     * 현재 봉을 제외해야 `close < donchian_lower(n)` 하향 돌파/지지 비교가 성립.
     */
    fun donchianLower(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 20)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val lowMin = KisIndicatorMath.rollingMin(KisIndicatorMath.lows(candles), period)
        val priorMin: List<BigDecimal?> = KisIndicatorMath.shift(lowMin, 1)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, priorMin, "lower"), SOURCE)
    }

    /** calc_chop indicators.py:1270-1280 — Choppiness Index */
    fun chop(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val tr = KisIndicatorMath.trueRange(candles) // calc_atr period=1 is just TR
        val trSum = KisIndicatorMath.rollingSum(tr, period)
        val highMax = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        val lowMin = KisIndicatorMath.rollingMin(KisIndicatorMath.lows(candles), period)
        val log10P = ln(period.toDouble()) / ln(10.0)
        val out =
            candles.indices.map { i ->
                val s = trSum[i]
                val h = highMax[i]
                val l = lowMin[i]
                if (s == null || h == null || l == null) {
                    null
                } else {
                    val denom = h.subtract(l, KisIndicatorMath.MC)
                    if (denom.signum() == 0 || s.signum() <= 0) {
                        BigDecimal.ZERO
                    } else {
                        val ratio = s.divide(denom, KisIndicatorMath.MC).toDouble()
                        if (ratio <= 0) {
                            BigDecimal.ZERO
                        } else {
                            BigDecimal.valueOf(100 * (ln(ratio) / ln(10.0)) / log10P)
                        }
                    }
                }
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "chop"), SOURCE)
    }

    /** calc_mass_index indicators.py:1306-1314 */
    fun massIndex(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 25)
        if (candles.size < period) throw IndicatorException.InsufficientCandlesException(period, candles.size)
        val hl = candles.map { c -> c.highRaw.subtract(c.lowRaw, KisIndicatorMath.MC) }
        val ema1 = KisIndicatorMath.ewm(hl, 9)
        val ema2 = KisIndicatorMath.ewm(ema1, 9)
        val ratio =
            candles.indices.map { i ->
                if (ema2[i].signum() == 0) BigDecimal.ZERO else ema1[i].divide(ema2[i], KisIndicatorMath.MC)
            }
        val sum = KisIndicatorMath.rollingSum(ratio, period)
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, sum, "mass"), SOURCE)
    }

    /** calc_augen indicators.py:1501-1506 — Augen Price Spike */
    fun augen(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val period = spec.intParam("period", 14)
        if (candles.size < period + 1) throw IndicatorException.InsufficientCandlesException(period + 1, candles.size)
        val closes = KisIndicatorMath.closes(candles)
        val logRet =
            candles.indices.map { i ->
                if (i == 0) {
                    BigDecimal.ZERO
                } else {
                    val prev = closes[i - 1]
                    if (prev.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        BigDecimal.valueOf(ln(closes[i].toDouble() / prev.toDouble()))
                    }
                }
            }
        val std = KisIndicatorMath.rollingStd(logRet, period)
        val out =
            candles.indices.map { i ->
                val s = std[i]
                if (s == null || s.signum() == 0) null else logRet[i].divide(s, KisIndicatorMath.MC)
            }
        return IndicatorResult(spec, KisIndicatorMath.toSeries(candles, out, "augen"), SOURCE)
    }
}
