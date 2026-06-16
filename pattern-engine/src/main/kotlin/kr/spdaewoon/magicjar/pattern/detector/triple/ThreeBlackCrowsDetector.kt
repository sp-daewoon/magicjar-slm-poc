package kr.spdaewoon.magicjar.pattern.detector.triple

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.o
import org.springframework.stereotype.Component

/**
 * ThreeBlackCrows — KIS `_three_black_crows` 1:1 port (R1 active).
 *
 * Source: candlestick.py L502-L512 · License: MIT
 *
 * Rule: 직전 3캔들 모두 bearish AND close 단조 감소 (c2.close < c1.close AND c3.close < c2.close) → -1 BEAR
 */
@Component
class ThreeBlackCrowsDetector : PatternDetector {
    override val id = "three_black_crows"

    override fun detect(candles: List<Candle>): DetectorResult {
        if (candles.size < 3) return DetectorResult(Direction.NEUTRAL)
        val last3 = candles.takeLast(3)
        if (last3.any { !CandleMath.isBearish(it.o, it.c) }) return DetectorResult(Direction.NEUTRAL)
        val (c1, c2, c3) = Triple(last3[0], last3[1], last3[2])
        return if (c2.c < c1.c && c3.c < c2.c) {
            DetectorResult(Direction.BEAR)
        } else {
            DetectorResult(Direction.NEUTRAL)
        }
    }
}
