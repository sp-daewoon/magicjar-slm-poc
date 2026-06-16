package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * LadderBottom — R2 stub (KIS L622 — `_simple_triple(df, "bullish")` delegation, 5캔들).
 *
 * Source: candlestick.py L622 + L562-L575 (R2 사이클 보강 — SimpleTripleHelper bullish) · License: MIT
 */
@Component
class LadderBottomDetector : PatternDetector {
    override val id = "ladder_bottom"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
