package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * HomingPigeon — R2 stub.
 *
 * Source: candlestick.py L421-L427 (R2 사이클 보강) · License: MIT
 */
@Component
class HomingPigeonDetector : PatternDetector {
    override val id = "homing_pigeon"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
