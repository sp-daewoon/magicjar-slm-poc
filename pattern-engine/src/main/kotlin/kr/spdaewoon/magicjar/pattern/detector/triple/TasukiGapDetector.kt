package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * TasukiGap — R2 stub (KIS `_tasuki_gap` 본체 — alias 3건의 base).
 *
 * Source: candlestick.py L582-L590 (R2 사이클 보강) · License: MIT
 *
 * 본체 활성화 시 alias 3건도 본 detector 결과 활용:
 * - up_down_gap_three_methods (L675) → 그대로 delegation
 * - downside_tasuki_gap (L676) → BEAR only filter
 * - upside_tasuki_gap (L677) → BULL only filter
 */
@Component
class TasukiGapDetector : PatternDetector {
    override val id = "tasuki_gap"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
