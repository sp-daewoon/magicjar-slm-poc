package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * StalledPattern — R2 stub (KIS L578 — `_simple_triple(df, "bearish")` delegation).
 *
 * Source: candlestick.py L578 + L562-L575 (R2 사이클 보강 — SimpleTripleHelper bearish) · License: MIT
 */
@Component
class StalledPatternDetector : PatternDetector {
    override val id = "stalled_pattern"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
