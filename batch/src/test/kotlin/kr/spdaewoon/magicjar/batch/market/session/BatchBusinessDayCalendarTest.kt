package kr.spdaewoon.magicjar.batch.market.session

import kr.spdaewoon.magicjar.batch.persistence.BatchMarketCalendarJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.BatchMarketCalendarJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.Optional

class BatchBusinessDayCalendarTest {
    private val repository = mock<BatchMarketCalendarJpaRepository>()
    private val calendar = BatchBusinessDayCalendar(repository)

    @Test
    fun `market_calendar 휴장 row 우선 — 2026-05-25 부처님오신날 대체휴무`() {
        val date = LocalDate.of(2026, 5, 25)
        whenever(repository.findById(date)).thenReturn(
            Optional.of(
                BatchMarketCalendarJpaEntity(
                    date = date,
                    isBusinessDay = false,
                    isHoliday = true,
                    holidayName = "부처님오신날",
                    source = "STATIC_SEED",
                ),
            ),
        )

        assertThat(calendar.isBusinessDay(date)).isFalse()
    }

    @Test
    fun `market_calendar row 없으면 평일 fallback`() {
        val date = LocalDate.of(2026, 5, 26)
        whenever(repository.findById(date)).thenReturn(Optional.empty())

        assertThat(calendar.isBusinessDay(date)).isTrue()
    }

    @Test
    fun `market_calendar row 없으면 주말 fallback false`() {
        val date = LocalDate.of(2026, 5, 24)
        whenever(repository.findById(date)).thenReturn(Optional.empty())

        assertThat(calendar.isBusinessDay(date)).isFalse()
    }
}
