package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.h
import kr.spdaewoon.magicjar.pattern.util.l
import kr.spdaewoon.magicjar.pattern.util.o
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * ShootingStar — KIS `_shooting_star` 1:1 port + magicJar 자체 추세 보강 (R1 active).
 *
 * Source: candlestick.py L157-L166 · License: MIT
 * R1 보강: 직전 5봉 상승추세 (catalog §정합 — 마지막 제외 직전 5봉 중 양봉 ≥ 3).
 *
 * Rule: b > 0 AND upper_shadow >= 2*body AND lower_shadow <= 0.3*body
 *       AND 직전 5봉 상승추세 → -1 BEAR
 */
@Component
class ShootingStarDetector : PatternDetector {
    override val id = "shooting_star"

    override fun detect(candles: List<Candle>): DetectorResult {
        val last = candles.last()
        val b = CandleMath.body(last.o, last.c)
        if (b.signum() == 0) return DetectorResult(Direction.NEUTRAL)
        val us = CandleMath.upperShadow(last.h, last.o, last.c)
        val ls = CandleMath.lowerShadow(last.o, last.c, last.l)
        if (!(us >= b * BigDecimal("2") && ls <= b * BigDecimal("0.3"))) {
            return DetectorResult(Direction.NEUTRAL)
        }
        // R1 보강: 직전 5봉 상승추세
        if (candles.size < 6) return DetectorResult(Direction.NEUTRAL)
        val priorWindow = candles.subList(candles.size - 6, candles.size - 1)
        val bullCount = priorWindow.count { CandleMath.isBullish(it.o, it.c) }
        if (bullCount < 3) return DetectorResult(Direction.NEUTRAL)
        return DetectorResult(
            Direction.BEAR,
            params =
                mapOf(
                    "upper_shadow_to_body" to us.divide(b, 4, java.math.RoundingMode.HALF_EVEN),
                    "prior_bull_count" to BigDecimal(bullCount),
                ),
        )
    }
}
