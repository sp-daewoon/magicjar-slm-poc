package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.o
import org.springframework.stereotype.Component

/**
 * Engulfing — KIS `_engulfing` 1:1 port (R1 active).
 *
 * Source: candlestick.py L248-L255 · License: MIT
 *
 * Rule:
 * - bullish: prev bearish AND curr bullish AND o2 <= c1 AND c2 >= o1 → +1 BULL
 * - bearish: prev bullish AND curr bearish AND o2 >= c1 AND c2 <= o1 → -1 BEAR
 */
@Component
class EngulfingDetector : PatternDetector {
    override val id = "engulfing"

    override fun detect(candles: List<Candle>): DetectorResult {
        if (candles.size < 2) return DetectorResult(Direction.NEUTRAL)
        val c1 = candles[candles.size - 2]
        val c2 = candles[candles.size - 1]
        val (o1, cl1) = c1.o to c1.c
        val (o2, cl2) = c2.o to c2.c
        return when {
            CandleMath.isBearish(o1, cl1) && CandleMath.isBullish(o2, cl2) && o2 <= cl1 && cl2 >= o1 -> {
                DetectorResult(Direction.BULL)
            }
            CandleMath.isBullish(o1, cl1) && CandleMath.isBearish(o2, cl2) && o2 >= cl1 && cl2 <= o1 -> {
                DetectorResult(Direction.BEAR)
            }
            else -> DetectorResult(Direction.NEUTRAL)
        }
    }
}
