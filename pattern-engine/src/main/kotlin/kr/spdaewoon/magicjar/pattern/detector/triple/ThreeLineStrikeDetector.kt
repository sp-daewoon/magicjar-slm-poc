package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * ThreeLineStrike — R2 stub (4 캔들).
 *
 * Source: candlestick.py L602-L614 (R2 사이클 보강) · License: MIT
 */
@Component
class ThreeLineStrikeDetector : PatternDetector {
    override val id = "three_line_strike"
    override val minCandles: Int = 5

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
