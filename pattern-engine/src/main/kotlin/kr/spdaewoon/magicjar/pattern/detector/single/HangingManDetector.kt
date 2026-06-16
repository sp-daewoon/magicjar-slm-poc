package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.helper.ConsecutiveDirectionCalculator
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.h
import kr.spdaewoon.magicjar.pattern.util.l
import kr.spdaewoon.magicjar.pattern.util.o
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * HangingMan — KIS `_hanging_man` 1:1 port + magicJar 자체 추세 보강 (R1 active).
 *
 * Source: candlestick.py L132-L142 · License: MIT
 * R1 보강: `pattern-catalog.md` §"R1 인식기 구현 범위" + `pr-38-kis-pattern-raw-verification.md` §5 정합
 *          — 직전 5봉 상승추세 (consecutive_up >= 3) 추가 의무 (KIS는 형태만 — 추세 검증은 magicJar own).
 *
 * Rule: r > 0 AND b > 0 AND lower_shadow >= 2*body AND upper_shadow <= 0.3*body
 *       AND 직전 5봉 상승추세 (last 캔들 제외 직전 5봉 중 양봉 3건 이상) → -1 BEAR
 */
@Component
class HangingManDetector : PatternDetector {
    override val id = "hanging_man"

    override fun detect(candles: List<Candle>): DetectorResult {
        val last = candles.last()
        val b = CandleMath.body(last.o, last.c)
        val r = CandleMath.range(last.h, last.l)
        if (r.signum() == 0 || b.signum() == 0) return DetectorResult(Direction.NEUTRAL)
        val ls = CandleMath.lowerShadow(last.o, last.c, last.l)
        val us = CandleMath.upperShadow(last.h, last.o, last.c)
        if (!(ls >= b * BigDecimal("2") && us <= b * BigDecimal("0.3"))) {
            return DetectorResult(Direction.NEUTRAL)
        }
        // R1 보강: 직전 5봉 상승추세 — 마지막 캔들 제외, 그 직전 5봉 중 양봉 ≥ 3
        if (candles.size < 6) return DetectorResult(Direction.NEUTRAL)
        val priorWindow = candles.subList(candles.size - 6, candles.size - 1)
        val bullCount = priorWindow.count { CandleMath.isBullish(it.o, it.c) }
        if (bullCount < 3) return DetectorResult(Direction.NEUTRAL)
        return DetectorResult(
            Direction.BEAR,
            params =
                mapOf(
                    "lower_shadow_to_body" to ls.divide(b, 4, java.math.RoundingMode.HALF_EVEN),
                    "prior_bull_count" to BigDecimal(bullCount),
                ),
        )
    }

    @Suppress("unused")
    private fun consecutiveUp(candles: List<Candle>): Int = ConsecutiveDirectionCalculator.consecutive("up", candles)
}
