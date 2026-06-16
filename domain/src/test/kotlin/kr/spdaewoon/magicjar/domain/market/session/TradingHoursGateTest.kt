package kr.spdaewoon.magicjar.domain.market.session

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * readiness P1 — 리플레이가 라이브와 동일한 정규장 게이트를 타도록 보장하는 도메인 단위 검증.
 *
 * [PerInstantMarketSessionResolver]는 라이브 KrxMarketSessionResolver의 KST FSM(09:00~15:29 Regular)을
 * Redis "현재 세션" 캐시 없이 임의 시각에 적용한다. boundary 별 판정이 정확해야 한다.
 */
class TradingHoursGateTest {
    // 영업일(평일) 술어 + 주말 비영업일 fallback.
    private val weekdayCalendar: (LocalDate) -> Boolean = { d ->
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
    }

    private fun gate(isBusinessDay: (LocalDate) -> Boolean = weekdayCalendar): TradingHoursGate =
        TradingHoursGate(sessionResolver = PerInstantMarketSessionResolver(isBusinessDay = isBusinessDay))

    @Test
    fun `regular session boundary on a business day is within trading hours`() {
        // 2026-06-02(화) 10:00 KST = 01:00Z
        gate().isWithinTradingHours(Instant.parse("2026-06-02T01:00:00Z")) shouldBe true
    }

    @Test
    fun `0900 KST entry boundary is within trading hours (off-by-one)`() {
        // 09:00 KST = 00:00Z
        gate().isWithinTradingHours(Instant.parse("2026-06-02T00:00:00Z")) shouldBe true
    }

    @Test
    fun `1530 KST close boundary is outside trading hours (off-by-one)`() {
        // 15:30 KST = 06:30Z — Regular는 15:29까지
        gate().isWithinTradingHours(Instant.parse("2026-06-02T06:30:00Z")) shouldBe false
    }

    @Test
    fun `pre market 0830 KST boundary is outside trading hours`() {
        // 08:30 KST = 23:30Z 전날
        gate().isWithinTradingHours(Instant.parse("2026-06-01T23:30:00Z")) shouldBe false
    }

    @Test
    fun `regular time on a weekend is outside trading hours`() {
        // 2026-06-06(토) 10:00 KST = 01:00Z
        gate().isWithinTradingHours(Instant.parse("2026-06-06T01:00:00Z")) shouldBe false
    }

    @Test
    fun `regular time on a calendar holiday is outside trading hours`() {
        // 평일이지만 캘린더가 휴장으로 판정 → 게이트 false.
        val holidayGate = gate(isBusinessDay = { false })
        holidayGate.isWithinTradingHours(Instant.parse("2026-06-02T01:00:00Z")) shouldBe false
    }
}
