package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.o
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * MorningStar — KIS `_morning_star` 1:1 port (R1 active).
 *
 * Source: candlestick.py L451-L457 · License: MIT
 *
 * Rule: c1 bearish AND body(c2) < body(c1)*0.3 AND c3 bullish AND close(c3) > (open(c1)+close(c1))/2 → +1 BULL
 */
@Component
class MorningStarDetector : PatternDetector {
    override val id = "morning_star"

    override fun detect(candles: List<Candle>): DetectorResult {
        if (candles.size < 3) return DetectorResult(Direction.NEUTRAL)
        val c1 = candles[candles.size - 3]
        val c2 = candles[candles.size - 2]
        val c3 = candles[candles.size - 1]
        val body1 = CandleMath.body(c1.o, c1.c)
        val body2 = CandleMath.body(c2.o, c2.c)
        val mid1 = (c1.o + c1.c).divide(BigDecimal("2"), 8, RoundingMode.HALF_EVEN)
        return if (CandleMath.isBearish(c1.o, c1.c) &&
            body2 < body1 * BigDecimal("0.3") &&
            CandleMath.isBullish(c3.o, c3.c) &&
            c3.c > mid1
        ) {
            DetectorResult(Direction.BULL, params = mapOf("body1" to body1, "body2" to body2))
        } else {
            DetectorResult(Direction.NEUTRAL)
        }
    }
}
