package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * ThreeStarsInSouth — R2 stub (KIS L576 — `_simple_triple(df, "bullish")` delegation).
 *
 * Source: candlestick.py L576 + L562-L575 (R2 사이클 보강 — SimpleTripleHelper 호출) · License: MIT
 */
@Component
class ThreeStarsInSouthDetector : PatternDetector {
    override val id = "three_stars_in_south"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
