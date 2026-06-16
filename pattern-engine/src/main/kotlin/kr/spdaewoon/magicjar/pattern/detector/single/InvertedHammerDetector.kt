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
 * InvertedHammer — KIS `_inverted_hammer` 1:1 port (R1 active).
 *
 * Source: candlestick.py L145-L154 · License: MIT
 *
 * Rule: b > 0 AND upper_shadow >= 2*body AND lower_shadow <= 0.3*body → +1 BULL
 */
@Component
class InvertedHammerDetector : PatternDetector {
    override val id = "inverted_hammer"

    override fun detect(candles: List<Candle>): DetectorResult {
        val last = candles.last()
        val b = CandleMath.body(last.o, last.c)
        if (b.signum() == 0) return DetectorResult(Direction.NEUTRAL)
        val us = CandleMath.upperShadow(last.h, last.o, last.c)
        val ls = CandleMath.lowerShadow(last.o, last.c, last.l)
        return if (us >= b * BigDecimal("2") && ls <= b * BigDecimal("0.3")) {
            DetectorResult(
                Direction.BULL,
                params = mapOf("upper_shadow_to_body" to us.divide(b, 4, java.math.RoundingMode.HALF_EVEN)),
            )
        } else {
            DetectorResult(Direction.NEUTRAL)
        }
    }
}
