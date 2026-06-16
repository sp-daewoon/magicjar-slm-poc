package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * Counterattack — R2 stub.
 *
 * Source: candlestick.py L300-L309 (R2 사이클 보강) · License: MIT
 */
@Component
class CounterattackDetector : PatternDetector {
    override val id = "counterattack"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
