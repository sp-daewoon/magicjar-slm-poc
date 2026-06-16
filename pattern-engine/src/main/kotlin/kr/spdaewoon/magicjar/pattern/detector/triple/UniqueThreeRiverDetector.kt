package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * UniqueThreeRiver — R2 stub (KIS L617 — `_simple_triple(df, "bullish")` delegation).
 *
 * Source: candlestick.py L617 + L562-L575 (R2 사이클 보강 — SimpleTripleHelper bullish) · License: MIT
 */
@Component
class UniqueThreeRiverDetector : PatternDetector {
    override val id = "unique_three_river"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
