package kr.spdaewoon.magicjar.pattern.helper

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.pattern.util.CandleMath
import kr.spdaewoon.magicjar.pattern.util.c
import kr.spdaewoon.magicjar.pattern.util.o

/**
 * KIS Strategy Builder candlestick.py `_simple_triple(df, direction)` Kotlin port.
 *
 * Source: candlestick.py L562-L575 · License: MIT
 *
 * 8 detector 공유 (R2 stub 시점은 본 helper 미사용 — 활성화 시 호출 의무):
 * - three_stars_in_south (L576) · advance_block (L577) · stalled_pattern (L578)
 * - unique_three_river (L617) · breakaway (L618) · mat_hold (L619)
 * - ladder_bottom (L622) · concealing_baby_swallow (L623)
 *
 * (rising_three_methods·falling_three_methods·deliberation는 magicJar catalog 제외 — Lean 미지원)
 *
 * KIS 룰 정합:
 * - bullish: 직전 3캔들 모두 양봉 + close 단조 증가 → +1
 * - bearish: 직전 3캔들 모두 음봉 + close 단조 감소 → -1
 * - 그 외 0
 */
object SimpleTripleHelper {
    /**
     * @param direction "bullish" | "bearish"
     * @return 1 if 3양봉 + close 단조 증가 (bullish 매칭) /
     *         -1 if 3음봉 + close 단조 감소 (bearish 매칭) / 0 미감지
     */
    fun simpleTriple(
        candles: List<Candle>,
        direction: String,
    ): Int {
        if (candles.size < 3) return 0
        val last3 = candles.takeLast(3)
        val (c1, c2, c3) = Triple(last3[0], last3[1], last3[2])

        return when (direction) {
            "bullish" -> {
                if (CandleMath.isBullish(c1.o, c1.c) &&
                    CandleMath.isBullish(c2.o, c2.c) &&
                    CandleMath.isBullish(c3.o, c3.c) &&
                    c2.c > c1.c &&
                    c3.c > c2.c
                ) {
                    1
                } else {
                    0
                }
            }
            "bearish" -> {
                if (CandleMath.isBearish(c1.o, c1.c) &&
                    CandleMath.isBearish(c2.o, c2.c) &&
                    CandleMath.isBearish(c3.o, c3.c) &&
                    c2.c < c1.c &&
                    c3.c < c2.c
                ) {
                    -1
                } else {
                    0
                }
            }
            else -> 0
        }
    }
}
