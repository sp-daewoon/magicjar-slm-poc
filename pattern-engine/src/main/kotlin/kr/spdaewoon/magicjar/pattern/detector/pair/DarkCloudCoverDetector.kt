package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * DarkCloudCover — R2 stub.
 *
 * Source: candlestick.py L291-L297 (R2 사이클 보강) · License: MIT
 */
@Component
class DarkCloudCoverDetector : PatternDetector {
    override val id = "dark_cloud_cover"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
