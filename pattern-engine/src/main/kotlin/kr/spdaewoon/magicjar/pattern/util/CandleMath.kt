package kr.spdaewoon.magicjar.pattern.util

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * KIS Strategy Builder candlestick.py 7 helper Kotlin port (ADR 0020 §3-1·§3-4).
 *
 * Source: candlestick.py L14-L36 (helper 6) + L71 (_avg_body) · License: MIT
 * Catalog: pr-38-kis-pattern-raw-verification.md §3 LOCK (자체 추론 0).
 *
 * 모든 raw 비교는 BigDecimal compareTo 의무 (==/!=는 scale 차이로 부정확).
 */
object CandleMath {
    /**
     * `_body(o, c) = abs(c - o)` (raw L14).
     */
    fun body(
        open: BigDecimal,
        close: BigDecimal,
    ): BigDecimal = (close - open).abs()

    /**
     * `_upper_shadow(h, o, c) = h - max(o, c)` (raw L18).
     */
    fun upperShadow(
        high: BigDecimal,
        open: BigDecimal,
        close: BigDecimal,
    ): BigDecimal = high - maxOf(open, close)

    /**
     * `_lower_shadow(o, c, l) = min(o, c) - l` (raw L22).
     */
    fun lowerShadow(
        open: BigDecimal,
        close: BigDecimal,
        low: BigDecimal,
    ): BigDecimal = minOf(open, close) - low

    /**
     * `_range(h, l) = h - l` (raw L26).
     */
    fun range(
        high: BigDecimal,
        low: BigDecimal,
    ): BigDecimal = high - low

    /**
     * `_is_bullish(o, c) = c > o` (raw L30).
     */
    fun isBullish(
        open: BigDecimal,
        close: BigDecimal,
    ): Boolean = close > open

    /**
     * `_is_bearish(o, c) = c < o` (raw L34).
     */
    fun isBearish(
        open: BigDecimal,
        close: BigDecimal,
    ): Boolean = close < open

    /**
     * `_avg_body(df, n=10)` (raw L71).
     *
     * @return 평균 몸통 BigDecimal scale=8 RoundingMode.HALF_EVEN.
     *         빈 입력 시 BigDecimal.ONE (KIS L73 `1.0` 정합).
     */
    fun avgBody(
        candles: List<Candle>,
        n: Int = 10,
    ): BigDecimal {
        val tail = candles.takeLast(n)
        if (tail.isEmpty()) return BigDecimal.ONE
        val sum = tail.fold(BigDecimal.ZERO) { acc, c -> acc + body(c.openRaw, c.closeRaw) }
        return sum.divide(BigDecimal(tail.size), 8, RoundingMode.HALF_EVEN)
    }
}

/**
 * Candle 확장 — KIS `_get_ohlc(df, idx)` 정합 (raw L62-L68).
 *
 * raw price 직접 사용 (수정주가 adj 아님 — KIS는 raw close/open만).
 */
internal val Candle.o: BigDecimal get() = openRaw
internal val Candle.h: BigDecimal get() = highRaw
internal val Candle.l: BigDecimal get() = lowRaw
internal val Candle.c: BigDecimal get() = closeRaw
