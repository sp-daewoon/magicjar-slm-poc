package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketDailyNightlySnapshotScheduler — ADR 0118 R2 cron 트리거 단위테스트.
 *
 * 검증 매트릭스:
 *   - 영업일 → 서비스 1회 발사.
 *   - 비거래일(휴장) → skip (서비스 호출 0).
 *   - 서비스 예외 → graceful (스케줄러는 throw 0 — 다음 영업일 재시도).
 */
class MarketDailyNightlySnapshotSchedulerTest {
    private val kst = ZoneId.of("Asia/Seoul")

    private fun newScheduler(
        service: MarketDailyNightlySnapshotService,
        calendar: BusinessDayCalendar,
    ) = MarketDailyNightlySnapshotScheduler(service, calendar)

    private fun completedResult() =
        MarketDailyNightlySnapshotService.SnapshotResult(
            runId = "r",
            status = MarketDailyNightlySnapshotService.SnapshotStatus.COMPLETED,
            universeSize = 10,
            chunks = 1,
            quotesReceived = 10,
            candlesMapped = 10,
            upsertedRows = 10,
            skippedQuotes = 0,
            rollupSymbols = 0,
            rollupWeekly = 0L,
            rollupMonthly = 0L,
            rollupYearly = 0L,
            durationMs = 5,
        )

    @Test
    fun `영업일이면 야간 snapshot 서비스에 오늘 날짜로 1회 발사`() {
        val targetDate = slot<LocalDate>()
        val service =
            mockk<MarketDailyNightlySnapshotService> {
                every { runSnapshot(capture(targetDate)) } returns completedResult()
            }
        val calendar = mockk<BusinessDayCalendar> { every { isBusinessDay(any()) } returns true }

        newScheduler(service, calendar).runNightlySnapshot()

        verify(exactly = 1) { service.runSnapshot(any<LocalDate>()) }
        assertEquals(LocalDate.now(kst), targetDate.captured)
    }

    @Test
    fun `비거래일이면 skip — 서비스 호출 0`() {
        val service = mockk<MarketDailyNightlySnapshotService>(relaxed = true)
        val calendar = mockk<BusinessDayCalendar> { every { isBusinessDay(any<LocalDate>()) } returns false }

        newScheduler(service, calendar).runNightlySnapshot()

        verify(exactly = 0) { service.runSnapshot(any<LocalDate>()) }
    }

    @Test
    fun `서비스 예외 시 graceful — 스케줄러는 throw 0 (다음 영업일 재시도)`() {
        val service =
            mockk<MarketDailyNightlySnapshotService> {
                every { runSnapshot(any<LocalDate>()) } throws RuntimeException("일시 오류")
            }
        val calendar = mockk<BusinessDayCalendar> { every { isBusinessDay(any()) } returns true }

        // throw 가 전파되면 테스트 실패 — 예외 없이 종료해야 함.
        newScheduler(service, calendar).runNightlySnapshot()

        verify(exactly = 1) { service.runSnapshot(any<LocalDate>()) }
    }

    @Test
    fun `장전 snapshot 은 오늘이 영업일이면 직전 영업일을 대상 날짜로 발사`() {
        val today = LocalDate.now(kst)
        val targetDate = slot<LocalDate>()
        val service =
            mockk<MarketDailyNightlySnapshotService> {
                every { runSnapshot(capture(targetDate)) } returns completedResult()
            }
        val calendar =
            mockk<BusinessDayCalendar> {
                every { isBusinessDay(today) } returns true
                every { isBusinessDay(today.minusDays(1)) } returns true
            }

        newScheduler(service, calendar).runPreOpenSnapshot()

        verify(exactly = 1) { service.runSnapshot(any<LocalDate>()) }
        assertEquals(today.minusDays(1), targetDate.captured)
    }
}
