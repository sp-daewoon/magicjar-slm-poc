package kr.spdaewoon.magicjar.api.service.trend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.trend.TrendPreference
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceDefaults
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceRepository
import org.junit.jupiter.api.Test
import java.time.Instant

class TrendPreferenceServiceTest {
    private val repo: TrendPreferenceRepository = mockk(relaxed = true)
    private val service = TrendPreferenceService(repo)

    private val userId = "12345678"
    private val now: Instant = Instant.parse("2026-05-24T10:00:00Z")

    @Test
    fun `getOrDefault — 사용자 row 미존재 시 default forUser 반환`() {
        every { repo.findByUserId(userId) } returns null
        val pref = service.getOrDefault(userId)
        pref.maPeriods shouldBe TrendPreferenceDefaults.MA_PERIODS
        pref.rsPeriod shouldBe TrendPreferenceDefaults.RS_PERIOD
        pref.isDefault() shouldBe true
        pref.updatedAt shouldBe Instant.EPOCH
    }

    @Test
    fun `getOrDefault — 사용자 row 존재 시 본 row 반환`() {
        val saved =
            TrendPreference(
                userId = userId,
                maPeriods = listOf(10, 30, 90),
                rsPeriod = 100,
                updatedAt = now,
            )
        every { repo.findByUserId(userId) } returns saved
        val pref = service.getOrDefault(userId)
        pref shouldBe saved
        pref.isDefault() shouldBe false
    }

    @Test
    fun `update — TrendPreference invariant 통과 시 upsert 호출`() {
        every { repo.upsert(any()) } answers { firstArg() }
        val result =
            service.update(
                userId = userId,
                maPeriods = listOf(5, 20, 60),
                rsPeriod = 60,
                crossPair = null,
            )
        result.maPeriods shouldBe listOf(5, 20, 60)
        verify(exactly = 1) { repo.upsert(any()) }
    }

    @Test
    fun `update — invariant 위반 시 upsert 호출 0건`() {
        shouldThrow<IllegalArgumentException> {
            service.update(
                userId = userId,
                maPeriods = listOf(5, 5, 60), // 중복
                rsPeriod = 60,
                crossPair = null,
            )
        }
        verify(exactly = 0) { repo.upsert(any()) }
    }

    @Test
    fun `update — crossPair 명시 통과`() {
        every { repo.upsert(any()) } answers { firstArg() }
        val result =
            service.update(
                userId = userId,
                maPeriods = listOf(5, 20, 60),
                rsPeriod = 60,
                crossPair = 5 to 60,
            )
        result.crossPair shouldNotBe null
        result.shortMa() shouldBe 5
        result.longMa() shouldBe 60
    }
}
