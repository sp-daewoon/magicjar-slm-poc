package kr.spdaewoon.magicjar.domain.market.candle

/**
 * Intraday candle session policy.
 *
 * ADR 0063 + ADR 0105 LOCK: KIS minute candles are collected in NXT-integrated
 * mode where available, so aggregation must not discard 08:00~09:00 or
 * 15:30~20:00 bars. KRX-only symbols naturally have no NXT bars in this window.
 */
object CandleSession {
    /** NXT integrated session start = KST 08:00. */
    const val NXT_INTEGRATED_OPEN_MIN: Int = 8 * 60

    /** NXT integrated session end = KST 20:00. */
    const val NXT_INTEGRATED_CLOSE_MIN: Int = 20 * 60

    val NINETY_MIN_START_MIN: IntArray =
        intArrayOf(
            8 * 60 + 0,
            9 * 60 + 30,
            11 * 60 + 0,
            12 * 60 + 30,
            14 * 60 + 0,
            15 * 60 + 30,
            17 * 60 + 0,
            18 * 60 + 30,
        )

    val NINETY_MIN_CLOSE_MIN: IntArray =
        intArrayOf(
            9 * 60 + 30,
            11 * 60 + 0,
            12 * 60 + 30,
            14 * 60 + 0,
            15 * 60 + 30,
            17 * 60 + 0,
            18 * 60 + 30,
            NXT_INTEGRATED_CLOSE_MIN,
        )

    fun isInIntegratedSession(minOfDay: Int): Boolean = minOfDay >= NXT_INTEGRATED_OPEN_MIN && minOfDay < NXT_INTEGRATED_CLOSE_MIN

    fun ninetyMinuteStart(minOfDay: Int): Int {
        if (minOfDay < NINETY_MIN_START_MIN.first()) {
            return NINETY_MIN_START_MIN.first()
        }
        var picked = NINETY_MIN_START_MIN.first()
        for (anchor in NINETY_MIN_START_MIN) {
            if (anchor <= minOfDay) picked = anchor else break
        }
        return picked
    }

    fun anchoredStart(
        minOfDay: Int,
        intervalMinutes: Int,
    ): Int {
        if (minOfDay < NXT_INTEGRATED_OPEN_MIN) return NXT_INTEGRATED_OPEN_MIN
        val offset = minOfDay - NXT_INTEGRATED_OPEN_MIN
        return NXT_INTEGRATED_OPEN_MIN + (offset / intervalMinutes) * intervalMinutes
    }
}
