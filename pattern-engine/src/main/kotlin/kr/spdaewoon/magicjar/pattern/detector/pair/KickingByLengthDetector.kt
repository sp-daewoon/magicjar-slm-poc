package kr.spdaewoon.magicjar.pattern.detector.pair

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * KickingByLength — R2 stub (KIS L443 — `_kicking` delegation).
 *
 * Source: candlestick.py L442-L443 (R2 사이클 보강 — `return _kicking(df)`) · License: MIT
 */
@Component
class KickingByLengthDetector : PatternDetector {
    override val id = "kicking_by_length"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
