package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * DownsideTasukiGap — R2 stub (KIS L676 — `_tasuki_gap` filter BEAR only).
 *
 * Source: candlestick.py L676 + L582-L590 (R2 사이클 보강 — TasukiGap 결과 -1 (bearish) only filter) · License: MIT
 */
@Component
class DownsideTasukiGapDetector : PatternDetector {
    override val id = "downside_tasuki_gap"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
