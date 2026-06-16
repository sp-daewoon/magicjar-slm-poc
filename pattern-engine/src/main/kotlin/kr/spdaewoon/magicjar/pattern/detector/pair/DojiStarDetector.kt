package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * DojiStar — R2 stub (KIS `_dojistar` → magicJar `doji_star` rename).
 *
 * Source: candlestick.py L430-L439 (R2 사이클 보강) · License: MIT
 */
@Component
class DojiStarDetector : PatternDetector {
    override val id = "doji_star"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
