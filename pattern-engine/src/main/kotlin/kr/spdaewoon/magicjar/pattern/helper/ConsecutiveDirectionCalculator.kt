package kr.spdaewoon.magicjar.pattern.helper

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.o

/**
 * magicJar own — KIS 미존재.
 *
 * Source: spec §8-7 LOCK + T-DSL [PatternRecognizerStub.consecutive] 시그니처 호환.
 *
 * DSL `consecutive("up"|"down") >= N` 호환.
 *
 * @param direction "up" (양봉 close > open) | "down" (음봉 close < open)
 * @return 마지막 캔들부터 직전으로 같은 방향 연속 횟수 (방향 mismatch 시 즉시 break)
 */
object ConsecutiveDirectionCalculator {
    fun consecutive(
        direction: String,
        candles: List<Candle>,
    ): Int {
        if (candles.isEmpty()) return 0
        val isMatch: (Candle) -> Boolean =
            when (direction) {
                "up" -> { c -> CandleMath.isBullish(c.o, c.c) }
                "down" -> { c -> CandleMath.isBearish(c.o, c.c) }
                else -> return 0
            }
        var count = 0
        for (i in candles.indices.reversed()) {
            if (isMatch(candles[i])) count++ else break
        }
        return count
    }
}
