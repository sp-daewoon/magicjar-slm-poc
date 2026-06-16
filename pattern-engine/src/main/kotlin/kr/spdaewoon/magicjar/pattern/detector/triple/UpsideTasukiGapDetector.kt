package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * UpsideTasukiGap — R2 stub (KIS L677 — `_tasuki_gap` filter BULL only).
 *
 * Source: candlestick.py L677 + L582-L590 (R2 사이클 보강 — TasukiGap 결과 +1 (bullish) only filter) · License: MIT
 */
@Component
class UpsideTasukiGapDetector : PatternDetector {
    override val id = "upside_tasuki_gap"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
