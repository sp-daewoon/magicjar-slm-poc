package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * BeltHold — R2 stub.
 *
 * Source: candlestick.py L216-L225 (R2 사이클 보강) · License: MIT
 *
 * KIS rule: body > avg*1.5 AND (bullish + lower_shadow < body*0.05 → +1) OR (bearish + upper_shadow < body*0.05 → -1)
 */
@Component
class BeltHoldDetector : PatternDetector {
    override val id = "belt_hold"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
