package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * Marubozu — R2 stub.
 *
 * Source: candlestick.py L169-L178 (R2 사이클 보강) · License: MIT
 *
 * KIS rule: r > 0 AND upper_shadow/r < 0.05 AND lower_shadow/r < 0.05 → +1 if bullish, -1 if bearish
 */
@Component
class MarubozuDetector : PatternDetector {
    override val id = "marubozu"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
