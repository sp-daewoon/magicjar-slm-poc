package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * ThreeOutside — R2 stub.
 *
 * Source: candlestick.py L531-L544 (R2 사이클 보강) · License: MIT
 */
@Component
class ThreeOutsideDetector : PatternDetector {
    override val id = "three_outside"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
