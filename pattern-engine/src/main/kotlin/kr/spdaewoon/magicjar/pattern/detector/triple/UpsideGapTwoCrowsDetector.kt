package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * UpsideGapTwoCrows — R2 stub.
 *
 * Source: candlestick.py L593-L599 (R2 사이클 보강) · License: MIT
 */
@Component
class UpsideGapTwoCrowsDetector : PatternDetector {
    override val id = "upside_gap_two_crows"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
