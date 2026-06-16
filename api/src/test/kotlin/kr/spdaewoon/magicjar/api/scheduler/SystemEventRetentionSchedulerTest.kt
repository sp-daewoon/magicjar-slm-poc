package kr.spdaewoon.magicjar.api.scheduler

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SystemEventRetentionSchedulerTest {
    @Test
    fun `enabled retention deletes rows older than configured days`() {
        val repository = mockk<SystemEventJpaRepository>()
        val cutoffSlot = slot<Instant>()
        every { repository.deleteOlderThan(capture(cutoffSlot)) } returns 42

        val before = Instant.now().minus(Duration.ofDays(7)).minusSeconds(2)
        SystemEventRetentionScheduler(
            systemEventJpaRepository = repository,
            enabled = true,
            retentionDays = 7,
        ).cleanupExpiredEvents()
        val after = Instant.now().minus(Duration.ofDays(7)).plusSeconds(2)

        assertThat(cutoffSlot.captured).isBetween(before, after)
        verify(exactly = 1) { repository.deleteOlderThan(any()) }
    }

    @Test
    fun `disabled retention does not touch repository`() {
        val repository = mockk<SystemEventJpaRepository>(relaxed = true)

        SystemEventRetentionScheduler(
            systemEventJpaRepository = repository,
            enabled = false,
            retentionDays = 7,
        ).cleanupExpiredEvents()

        verify(exactly = 0) { repository.deleteOlderThan(any()) }
    }

    @Test
    fun `retention days below one is coerced to one day`() {
        val repository = mockk<SystemEventJpaRepository>()
        val cutoffSlot = slot<Instant>()
        every { repository.deleteOlderThan(capture(cutoffSlot)) } returns 0

        val before = Instant.now().minus(Duration.ofDays(1)).minusSeconds(2)
        SystemEventRetentionScheduler(
            systemEventJpaRepository = repository,
            enabled = true,
            retentionDays = 0,
        ).cleanupExpiredEvents()
        val after = Instant.now().minus(Duration.ofDays(1)).plusSeconds(2)

        assertThat(cutoffSlot.captured).isBetween(before, after)
        verify(exactly = 1) { repository.deleteOlderThan(any()) }
    }
}
