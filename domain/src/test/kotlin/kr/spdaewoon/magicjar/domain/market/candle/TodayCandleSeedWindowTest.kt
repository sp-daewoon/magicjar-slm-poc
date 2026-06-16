package kr.spdaewoon.magicjar.domain.market.candle

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class TodayCandleSeedWindowTest {
    @Test
    fun `weekday pre-open skips today candle seed`() {
        val preOpen = Instant.parse("2026-06-10T18:54:00Z") // 2026-06-11 03:54 KST

        TodayCandleSeedWindow.shouldSkipSeed(preOpen) shouldBe true
    }

    @Test
    fun `integrated session open allows today candle seed`() {
        val integratedOpen = Instant.parse("2026-06-10T23:00:00Z") // 2026-06-11 08:00 KST

        TodayCandleSeedWindow.shouldSkipSeed(integratedOpen) shouldBe false
    }

    @Test
    fun `weekend skips today candle seed`() {
        val saturdayNoon = Instant.parse("2026-06-13T03:00:00Z") // 2026-06-13 12:00 KST

        TodayCandleSeedWindow.shouldSkipSeed(saturdayNoon) shouldBe true
    }

    @Test
    fun `pre-open skips only today's redis daily candle`() {
        val preOpen = Instant.parse("2026-06-10T18:54:00Z") // 2026-06-11 03:54 KST
        val yesterdayCandle = Instant.parse("2026-06-09T15:00:00Z") // 2026-06-10 00:00 KST
        val todayCandle = Instant.parse("2026-06-10T15:00:00Z") // 2026-06-11 00:00 KST

        TodayCandleSeedWindow.shouldSkipRedisCandle(preOpen, yesterdayCandle) shouldBe false
        TodayCandleSeedWindow.shouldSkipRedisCandle(preOpen, todayCandle) shouldBe true
    }
}
