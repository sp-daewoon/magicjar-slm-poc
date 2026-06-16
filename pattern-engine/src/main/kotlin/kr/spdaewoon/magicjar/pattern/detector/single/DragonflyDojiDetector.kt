package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import org.springframework.stereotype.Component

/**
 * DragonflyDoji — R2 stub.
 *
 * Source: candlestick.py L89-L96 (R2 사이클 보강 — 활성 시 KIS 본문 port 의무) · License: MIT
 *
 * KIS rule: r > 0 AND body/r < 0.1 AND lower_shadow/r > 0.6 AND upper_shadow/r < 0.1 → +1 BULL
 */
@Component
class DragonflyDojiDetector : PatternDetector {
    override val id = "dragonfly_doji"

    override fun detect(candles: List<Candle>): DetectorResult = DetectorResult(Direction.NEUTRAL)
}
