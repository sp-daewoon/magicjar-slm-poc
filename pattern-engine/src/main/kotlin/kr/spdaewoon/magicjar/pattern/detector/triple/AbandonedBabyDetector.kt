package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * AbandonedBaby — R2 stub.
 *
 * Source: candlestick.py L547-L559 (R2 사이클 보강) · License: MIT
 */
@Component
class AbandonedBabyDetector : PatternDetector {
    override val id = "abandoned_baby"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
