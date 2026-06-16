package kr.spdaewoon.magicjar.pattern.detector.single

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.DetectorResult
import kr.spdaewoon.magicjar.domain.pattern.Direction
import kr.spdaewoon.magicjar.domain.pattern.port.PatternDetector
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.h
import kr.spdaewoon.magicjar.pattern.util.l
import kr.spdaewoon.magicjar.pattern.util.o
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Doji — KIS `_doji` 1:1 port (R1 active).
 *
 * Source: candlestick.py L81-L86 · License: MIT
 *
 * Rule: `r > 0` AND `body / r < 0.1` → +1 (KIS는 BULL — magicJar는 NEUTRAL 카테고리지만 KIS 정합으로 BULL 반환).
 */
@Component
class DojiDetector : PatternDetector {
    override val id = "doji"

    override fun detect(candles: List<Candle>): DetectorResult {
        val last = candles.last()
        val r = CandleMath.range(last.h, last.l)
        if (r.signum() == 0) return DetectorResult(Direction.NEUTRAL)
        val b = CandleMath.body(last.o, last.c)
        val ratio = b.divide(r, 8, RoundingMode.HALF_EVEN)
        return if (ratio < BigDecimal("0.1")) {
            DetectorResult(Direction.BULL, params = mapOf("body_ratio" to ratio))
        } else {
            DetectorResult(Direction.NEUTRAL)
        }
    }
}
