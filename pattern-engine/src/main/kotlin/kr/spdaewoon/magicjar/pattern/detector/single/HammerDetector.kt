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
 * Hammer — KIS `_hammer` 1:1 port (R1 active).
 *
 * Source: candlestick.py L119-L129 · License: MIT
 *
 * Rule: r > 0 AND b > 0 AND lower_shadow >= 2*body AND upper_shadow <= 0.3*body → +1 BULL
 */
@Component
class HammerDetector : PatternDetector {
    override val id = "hammer"

    override fun detect(candles: List<Candle>): DetectorResult {
        val last = candles.last()
        val b = CandleMath.body(last.o, last.c)
        val r = CandleMath.range(last.h, last.l)
        if (r.signum() == 0 || b.signum() == 0) return DetectorResult(Direction.NEUTRAL)
        val ls = CandleMath.lowerShadow(last.o, last.c, last.l)
        val us = CandleMath.upperShadow(last.h, last.o, last.c)
        return if (ls >= b * BigDecimal("2") && us <= b * BigDecimal("0.3")) {
            DetectorResult(Direction.BULL, params = mapOf("lower_shadow_to_body" to ls.divide(b, 4, java.math.RoundingMode.HALF_EVEN)))
        } else {
            DetectorResult(Direction.NEUTRAL)
        }
    }
}
