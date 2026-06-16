package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * RickshawMan — R2 stub (KIS L240 — `_long_legged_doji` delegation).
 *
 * Source: candlestick.py L239-L240 (R2 사이클 보강 — `return _long_legged_doji(df)`) · License: MIT
 */
@Component
class RickshawManDetector : PatternDetector {
    override val id = "rickshaw_man"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
