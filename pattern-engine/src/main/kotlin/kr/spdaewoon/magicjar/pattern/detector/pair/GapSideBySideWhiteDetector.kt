package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * GapSideBySideWhite — R2 stub (Side by Side White Lines 통합).
 *
 * Source: candlestick.py L412-L418 (R2 사이클 보강 — KIS L678 `_side_by_side_white_lines` 통합 정합) · License: MIT
 */
@Component
class GapSideBySideWhiteDetector : PatternDetector {
    override val id = "gap_side_by_side_white"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
