package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * HighWave — R2 stub.
 *
 * Source: candlestick.py L228-L236 (R2 사이클 보강) · License: MIT
 *
 * KIS rule: r > 0 AND body/r < 0.15 AND upper_shadow/r > 0.3 AND lower_shadow/r > 0.3 → +1
 */
@Component
class HighWaveDetector : PatternDetector {
    override val id = "high_wave"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
