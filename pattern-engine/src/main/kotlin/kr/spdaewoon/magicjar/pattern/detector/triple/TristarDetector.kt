package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * Tristar — R2 stub.
 *
 * Source: candlestick.py L636-L652 (R2 사이클 보강) · License: MIT
 */
@Component
class TristarDetector : PatternDetector {
    override val id = "tristar"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
