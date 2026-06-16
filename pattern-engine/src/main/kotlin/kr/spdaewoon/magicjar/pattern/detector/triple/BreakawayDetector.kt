package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * Breakaway — R2 stub (KIS L618 — `_simple_triple(df, "bullish")` delegation, KIS는 simplified 5캔들 정합).
 *
 * Source: candlestick.py L618 + L562-L575 (R2 사이클 보강 — SimpleTripleHelper bullish) · License: MIT
 */
@Component
class BreakawayDetector : PatternDetector {
    override val id = "breakaway"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
