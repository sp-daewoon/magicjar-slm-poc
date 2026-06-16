package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * UpDownGapThreeMethods — R2 stub (KIS L675 — `_tasuki_gap` delegation).
 *
 * Source: candlestick.py L675 + L582-L590 (R2 사이클 보강 — TasukiGap 본체 결과 그대로 라우팅) · License: MIT
 */
@Component
class UpDownGapThreeMethodsDetector : PatternDetector {
    override val id = "up_down_gap_three_methods"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
