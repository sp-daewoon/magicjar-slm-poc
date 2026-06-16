package kr.spdaewoon.magicjar.indicator.impl.smartmoney

import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.math.BigDecimal
import java.math.RoundingMode

internal object SmartMoneyIndicators {
    private val ZERO = BigDecimal.ZERO
    private val ONE = BigDecimal.ONE
    private val HUNDRED = BigDecimal("100")

    private enum class Side {
        BULLISH,
        BEARISH,
    }

    private data class Zone(
        val side: Side,
        val top: BigDecimal,
        val bottom: BigDecimal,
        val originIndex: Int,
    ) {
        val mid: BigDecimal = top.add(bottom).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
    }

    fun fvg(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val mitigationPct = pctParam(spec, "mitigationPct", BigDecimal("50"))
        var bullish: Zone? = null
        var bearish: Zone? = null
        val series = mutableListOf<IndicatorPoint>()

        for (i in candles.indices) {
            val current = candles[i]
            bullish = bullish?.takeUnless { i > it.originIndex && isMitigated(it, current, mitigationPct) }
            bearish = bearish?.takeUnless { i > it.originIndex && isMitigated(it, current, mitigationPct) }

            if (i >= 2) {
                val left = candles[i - 2]
                if (current.lowRaw > left.highRaw) {
                    bullish =
                        Zone(
                            side = Side.BULLISH,
                            top = current.lowRaw,
                            bottom = left.highRaw,
                            originIndex = i,
                        )
                }
                if (current.highRaw < left.lowRaw) {
                    bearish =
                        Zone(
                            side = Side.BEARISH,
                            top = left.lowRaw,
                            bottom = current.highRaw,
                            originIndex = i,
                        )
                }
            }

            series.add(
                IndicatorPoint(
                    time = current.ts,
                    values = fvgValues(bullish, bearish, current),
                ),
            )
        }

        return IndicatorResult(spec, series, "smart-money")
    }

    /**
     * 오더블록(Order Block) 탐지.
     *
     * 파라미터 (add-only — ADR add-only, 위치 직렬화 계약 정합):
     * - `detection` ∈ {"bos"(default), "engulfing"} — 탐지 방식
     *   - `"bos"`: swing 돌파(Break of Structure) 후 반대색 캔들을 OB로 채택 (기존 동작 LOCK)
     *   - `"engulfing"`: 유튜버 장악형 — 직전 캔들 몸통을 완전히 감싸는(engulf) 캔들 패턴을
     *     기준봉(=장악당한 직전 캔들)으로 OB 채택. swingLookback/searchLookback은 무시
     * - `range` ∈ {"wick"(default), "body"} — zone 범위 산정
     *   - `"wick"`: top=기준봉.high, bottom=기준봉.low (기존 동작 LOCK)
     *   - `"body"`: top=max(open,close), bottom=min(open,close) — "장악당한 캔들의 몸통"
     *
     * 출력 스키마(12 키)·소멸 로직(mitigationPct + invalidation)은 무변경.
     * `bos_direction` 키는 detection="engulfing"일 때 BOS가 아닌 "장악 방향"을 의미한다
     * (+1=bullish 장악, -1=bearish 장악). 키 이름은 하위호환을 위해 유지.
     */
    fun orderBlock(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val detection = spec.stringParam("detection", "bos").lowercase()
        val rangeMode = spec.stringParam("range", "wick").lowercase()
        val mitigationPct = pctParam(spec, "mitigationPct", BigDecimal("50"))

        return when (detection) {
            "engulfing" -> orderBlockEngulfing(spec, candles, rangeMode, mitigationPct)
            else -> orderBlockBos(spec, candles, rangeMode, mitigationPct)
        }
    }

    private fun orderBlockBos(
        spec: IndicatorSpec,
        candles: List<Candle>,
        rangeMode: String,
        mitigationPct: BigDecimal,
    ): IndicatorResult {
        val swingLookback = spec.intParam("swingLookback", 20).coerceAtLeast(2)
        val searchLookback = spec.intParam("searchLookback", 20).coerceAtLeast(1)
        var bullish: Zone? = null
        var bearish: Zone? = null
        val series = mutableListOf<IndicatorPoint>()

        for (i in candles.indices) {
            val current = candles[i]
            bullish = bullish?.takeUnless { i > it.originIndex && (isInvalidated(it, current) || isMitigated(it, current, mitigationPct)) }
            bearish = bearish?.takeUnless { i > it.originIndex && (isInvalidated(it, current) || isMitigated(it, current, mitigationPct)) }

            var bosDirection = ZERO
            if (i >= swingLookback) {
                val swingWindow = candles.subList(i - swingLookback, i)
                val swingHigh = swingWindow.maxOf { it.highRaw }
                val swingLow = swingWindow.minOf { it.lowRaw }
                val searchStart = (i - searchLookback).coerceAtLeast(0)
                val searchWindow = candles.subList(searchStart, i)

                if (current.closeRaw > swingHigh) {
                    val ob = searchWindow.asReversed().firstOrNull { it.closeRaw < it.openRaw }
                    if (ob != null) {
                        bullish = zoneOf(Side.BULLISH, ob, rangeMode, i)
                        bosDirection = ONE
                    }
                } else if (current.closeRaw < swingLow) {
                    val ob = searchWindow.asReversed().firstOrNull { it.closeRaw > it.openRaw }
                    if (ob != null) {
                        bearish = zoneOf(Side.BEARISH, ob, rangeMode, i)
                        bosDirection = BigDecimal("-1")
                    }
                }
            }

            series.add(
                IndicatorPoint(
                    time = current.ts,
                    values = orderBlockValues(bullish, bearish, current, bosDirection),
                ),
            )
        }

        return IndicatorResult(spec, series, "smart-money")
    }

    private fun orderBlockEngulfing(
        spec: IndicatorSpec,
        candles: List<Candle>,
        rangeMode: String,
        mitigationPct: BigDecimal,
    ): IndicatorResult {
        var bullish: Zone? = null
        var bearish: Zone? = null
        val series = mutableListOf<IndicatorPoint>()

        for (i in candles.indices) {
            val current = candles[i]
            bullish = bullish?.takeUnless { i > it.originIndex && (isInvalidated(it, current) || isMitigated(it, current, mitigationPct)) }
            bearish = bearish?.takeUnless { i > it.originIndex && (isInvalidated(it, current) || isMitigated(it, current, mitigationPct)) }

            var bosDirection = ZERO
            if (i >= 1) {
                val prev = candles[i - 1]
                val curr = current
                val prevBearish = prev.closeRaw < prev.openRaw
                val prevBullish = prev.closeRaw > prev.openRaw
                val currBullish = curr.closeRaw > curr.openRaw
                val currBearish = curr.closeRaw < curr.openRaw

                // Bullish OB: 직전 음봉을 현재 양봉의 몸통이 완전히 감쌈 (경계 동등 허용)
                val bullishEngulf =
                    prevBearish && currBullish && curr.openRaw <= prev.closeRaw && curr.closeRaw >= prev.openRaw
                // Bearish OB: 직전 양봉을 현재 음봉의 몸통이 완전히 감쌈
                val bearishEngulf =
                    prevBullish && currBearish && curr.openRaw >= prev.closeRaw && curr.closeRaw <= prev.openRaw

                if (bullishEngulf) {
                    bullish = zoneOf(Side.BULLISH, prev, rangeMode, i)
                    bosDirection = ONE
                } else if (bearishEngulf) {
                    bearish = zoneOf(Side.BEARISH, prev, rangeMode, i)
                    bosDirection = BigDecimal("-1")
                }
            }

            series.add(
                IndicatorPoint(
                    time = current.ts,
                    values = orderBlockValues(bullish, bearish, current, bosDirection),
                ),
            )
        }

        return IndicatorResult(spec, series, "smart-money")
    }

    /**
     * 기준봉(origin candle) → Zone. range 모드에 따라 top/bottom 산정.
     * - "body": 몸통(max/min of open,close)
     * - 그 외("wick" default): 고가/저가 (full wick)
     */
    private fun zoneOf(
        side: Side,
        origin: Candle,
        rangeMode: String,
        originIndex: Int,
    ): Zone =
        if (rangeMode == "body") {
            val top = origin.openRaw.max(origin.closeRaw)
            val bottom = origin.openRaw.min(origin.closeRaw)
            Zone(side, top = top, bottom = bottom, originIndex = originIndex)
        } else {
            Zone(side, top = origin.highRaw, bottom = origin.lowRaw, originIndex = originIndex)
        }

    private fun fvgValues(
        bullish: Zone?,
        bearish: Zone?,
        current: Candle,
    ): Map<String, BigDecimal> =
        mapOf(
            "fvg" to bool(bullish != null || bearish != null),
            "bullish_active" to bool(bullish != null),
            "bearish_active" to bool(bearish != null),
            "bullish_top" to (bullish?.top ?: ZERO),
            "bullish_bottom" to (bullish?.bottom ?: ZERO),
            "bullish_mid" to (bullish?.mid ?: ZERO),
            "bullish_fill_pct" to (bullish?.let { fillPct(it, current) } ?: ZERO),
            "bearish_top" to (bearish?.top ?: ZERO),
            "bearish_bottom" to (bearish?.bottom ?: ZERO),
            "bearish_mid" to (bearish?.mid ?: ZERO),
            "bearish_fill_pct" to (bearish?.let { fillPct(it, current) } ?: ZERO),
        )

    private fun orderBlockValues(
        bullish: Zone?,
        bearish: Zone?,
        current: Candle,
        bosDirection: BigDecimal,
    ): Map<String, BigDecimal> =
        mapOf(
            "order_block" to bool(bullish != null || bearish != null),
            "bullish_active" to bool(bullish != null),
            "bearish_active" to bool(bearish != null),
            "bullish_top" to (bullish?.top ?: ZERO),
            "bullish_bottom" to (bullish?.bottom ?: ZERO),
            "bullish_mid" to (bullish?.mid ?: ZERO),
            "bullish_fill_pct" to (bullish?.let { fillPct(it, current) } ?: ZERO),
            "bearish_top" to (bearish?.top ?: ZERO),
            "bearish_bottom" to (bearish?.bottom ?: ZERO),
            "bearish_mid" to (bearish?.mid ?: ZERO),
            "bearish_fill_pct" to (bearish?.let { fillPct(it, current) } ?: ZERO),
            "bos_direction" to bosDirection,
        )

    private fun isMitigated(
        zone: Zone,
        candle: Candle,
        mitigationPct: BigDecimal,
    ): Boolean =
        when (zone.side) {
            Side.BULLISH -> candle.lowRaw <= mitigationPrice(zone, mitigationPct)
            Side.BEARISH -> candle.highRaw >= mitigationPrice(zone, mitigationPct)
        }

    private fun isInvalidated(
        zone: Zone,
        candle: Candle,
    ): Boolean =
        when (zone.side) {
            Side.BULLISH -> candle.closeRaw < zone.bottom
            Side.BEARISH -> candle.closeRaw > zone.top
        }

    private fun mitigationPrice(
        zone: Zone,
        mitigationPct: BigDecimal,
    ): BigDecimal {
        val range = zone.top.subtract(zone.bottom)
        val amount = range.multiply(mitigationPct).divide(HUNDRED, 8, RoundingMode.HALF_UP)
        return when (zone.side) {
            Side.BULLISH -> zone.top.subtract(amount)
            Side.BEARISH -> zone.bottom.add(amount)
        }
    }

    private fun fillPct(
        zone: Zone,
        candle: Candle,
    ): BigDecimal {
        val range = zone.top.subtract(zone.bottom)
        if (range.compareTo(ZERO) <= 0) return ZERO
        val filled =
            when (zone.side) {
                Side.BULLISH -> zone.top.subtract(candle.lowRaw).coerceAtLeastZero()
                Side.BEARISH -> candle.highRaw.subtract(zone.bottom).coerceAtLeastZero()
            }
        return filled
            .multiply(HUNDRED)
            .divide(range, 4, RoundingMode.HALF_UP)
            .coerceInZeroToHundred()
    }

    private fun pctParam(
        spec: IndicatorSpec,
        key: String,
        default: BigDecimal,
    ): BigDecimal = spec.bdParam(key, default).coerceInZeroToHundred()

    private fun bool(value: Boolean): BigDecimal = if (value) ONE else ZERO

    private fun BigDecimal.coerceAtLeastZero(): BigDecimal = if (this < ZERO) ZERO else this

    private fun BigDecimal.coerceInZeroToHundred(): BigDecimal =
        when {
            this < ZERO -> ZERO
            this > HUNDRED -> HUNDRED
            else -> this
        }
}
