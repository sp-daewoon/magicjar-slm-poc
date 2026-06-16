package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * LongLeggedDoji — R2 stub.
 *
 * Source: candlestick.py L109-L116 (R2 사이클 보강) · License: MIT
 *
 * KIS rule: r > 0 AND body/r < 0.1 AND upper_shadow/r > 0.3 AND lower_shadow/r > 0.3 → +1
 */
@Component
class LongLeggedDojiDetector : PatternDetector {
    override val id = "long_legged_doji"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
