package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * SpinningTop — R2 stub.
 *
 * Source: candlestick.py L205-L213 (R2 사이클 보강) · License: MIT
 *
 * KIS rule: body/r < 0.3 AND upper_shadow > body AND lower_shadow > body → +1
 */
@Component
class SpinningTopDetector : PatternDetector {
    override val id = "spinning_top"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
