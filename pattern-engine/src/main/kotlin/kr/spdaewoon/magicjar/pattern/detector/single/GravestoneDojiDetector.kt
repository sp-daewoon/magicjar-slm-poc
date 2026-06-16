package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * GravestoneDoji — R2 stub.
 *
 * Source: candlestick.py L99-L106 (R2 사이클 보강) · License: MIT
 *
 * KIS rule: r > 0 AND body/r < 0.1 AND upper_shadow/r > 0.6 AND lower_shadow/r < 0.1 → -1 BEAR
 */
@Component
class GravestoneDojiDetector : PatternDetector {
    override val id = "gravestone_doji"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
