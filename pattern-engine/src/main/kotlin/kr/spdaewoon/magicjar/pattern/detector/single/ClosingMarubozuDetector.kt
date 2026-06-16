package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * ClosingMarubozu — R2 stub.
 *
 * Source: candlestick.py L181-L190 (R2 사이클 보강) · License: MIT
 *
 * KIS rule:
 * - bullish + upper_shadow/r < 0.05 → +1
 * - bearish + lower_shadow/r < 0.05 → -1
 */
@Component
class ClosingMarubozuDetector : PatternDetector {
    override val id = "closing_marubozu"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
