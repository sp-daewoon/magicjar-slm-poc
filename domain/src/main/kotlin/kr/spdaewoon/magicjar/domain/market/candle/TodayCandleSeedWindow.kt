package kr.spdaewoon.magicjar.domain.market.candle

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Live today 1d candle seed window.
 *
 * KIS daily-itemchart output1 can still expose the previous trading day's OHLCV
 * before the integrated session starts. Seeding that payload with today's
 * midnight timestamp creates a duplicated-looking "today" candle on charts.
 */
object TodayCandleSeedWindow {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    fun shouldSkipSeed(
        now: Instant,
        zone: ZoneId = KST,
    ): Boolean {
        val kstNow = now.atZone(zone)
        if (kstNow.dayOfWeek == DayOfWeek.SATURDAY || kstNow.dayOfWeek == DayOfWeek.SUNDAY) return true

        val minuteOfDay = kstNow.hour * 60 + kstNow.minute
        return minuteOfDay < CandleSession.NXT_INTEGRATED_OPEN_MIN
    }

    fun shouldSkipRedisCandle(
        now: Instant,
        candleTs: Instant,
        zone: ZoneId = KST,
    ): Boolean {
        val kstNow = now.atZone(zone)
        val candleDate = candleTs.atZone(zone).toLocalDate()
        if (candleDate != kstNow.toLocalDate()) return false
        return shouldSkipSeed(now, zone)
    }
}
