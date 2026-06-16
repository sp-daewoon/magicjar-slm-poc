package kr.spdaewoon.magicjar.api.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import kr.spdaewoon.magicjar.persistence.shared.notification.outbox.NotificationOutboxJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.notification.outbox.NotificationOutboxJpaRepository
import kr.spdaewoon.magicjar.persistence.shared.notification.outbox.NotificationOutboxRepositoryAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * NotificationOutboxRepositoryAdapter 단위 테스트 (spec §2-4 LOCK).
 *
 * 5 케이스:
 *  (1) save — outbox → JPA entity 변환 후 저장
 *  (2) findPending — PENDING 상태 조회
 *  (3) markSent — status=SENT, sentAt 업데이트
 *  (4) markFailed — status=FAILED, lastError 기록
 *  (5) incrementRetryCount — retryCount++
 *
 * AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2).
 */
class NotificationOutboxRepositoryAdapterTest {
    private val jpaRepository = mockk<NotificationOutboxJpaRepository>(relaxed = true)

    // ADR 0094 P0-OPS-6 — PersistenceMetrics ObjectProvider mock (graceful skip)
    private val metricsProvider =
        mockk<ObjectProvider<PersistenceMetrics>>(relaxed = true).also {
            every { it.getIfAvailable() } returns null
        }
    private val adapter = NotificationOutboxRepositoryAdapter(jpaRepository, metricsProvider)

    private fun sampleOutbox(): NotificationOutbox =
        NotificationOutbox(
            id = null,
            eventId = UUID.randomUUID(),
            channel = NotificationChannel.SLACK,
            priority = NotificationPriority.P0,
            payloadJson = """{"eventType":"KILLSWITCH_ENGAGED","userId":"U1","reason":"test"}""",
            status = NotificationStatus.PENDING,
            retryCount = 0,
            createdAt = Instant.now(),
        )

    private fun sampleJpaEntity(
        id: Long,
        status: String = "PENDING",
        retryCount: Int = 0,
    ) = NotificationOutboxJpaEntity(
        id = id,
        eventId = UUID.randomUUID(),
        channel = "SLACK",
        priority = "P0",
        payload = """{"test":1}""",
        status = status,
        retryCount = retryCount,
        createdAt = Instant.now(),
    )

    @Test
    fun `(1) save — JPA entity 변환 후 저장 + domain 변환 반환`() {
        val outbox = sampleOutbox()
        val entity = sampleJpaEntity(id = 1L)
        every { jpaRepository.save(any<NotificationOutboxJpaEntity>()) } returns entity

        val result = adapter.save(outbox)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.status).isEqualTo(NotificationStatus.PENDING)
        val savedSlot = slot<NotificationOutboxJpaEntity>()
        verify { jpaRepository.save(capture(savedSlot)) }
        assertThat(savedSlot.captured.channel).isEqualTo("SLACK")
        assertThat(savedSlot.captured.priority).isEqualTo("P0")
    }

    @Test
    fun `(2) findPending — PENDING 상태 조회`() {
        val entities = listOf(sampleJpaEntity(1L, "PENDING"), sampleJpaEntity(2L, "PENDING"))
        every { jpaRepository.findByStatusOrderByCreatedAtAsc(eq("PENDING"), any<Pageable>()) } returns entities

        val result = adapter.findPending(limit = 50)

        assertThat(result).hasSize(2)
        assertThat(result.all { it.status == NotificationStatus.PENDING }).isTrue()
    }

    @Test
    fun `(3) markSent — status=SENT sentAt 업데이트`() {
        val entity = sampleJpaEntity(id = 1L, status = "PENDING")
        val sentEntity = sampleJpaEntity(id = 1L, status = "SENT")
        every { jpaRepository.findById(1L) } returns Optional.of(entity)
        every { jpaRepository.save(any()) } returns sentEntity

        val result = adapter.markSent(1L)

        assertThat(result).isNotNull
        assertThat(result!!.status).isEqualTo(NotificationStatus.SENT)
    }

    @Test
    fun `(4) markFailed — status=FAILED lastError 기록`() {
        val entity = sampleJpaEntity(id = 2L, status = "PENDING")
        val failedEntity = sampleJpaEntity(id = 2L, status = "FAILED")
        every { jpaRepository.findById(2L) } returns Optional.of(entity)
        every { jpaRepository.save(any()) } returns failedEntity

        val result = adapter.markFailed(2L, "retry 소진")

        assertThat(result!!.status).isEqualTo(NotificationStatus.FAILED)
        val entitySlot = slot<NotificationOutboxJpaEntity>()
        verify { jpaRepository.save(capture(entitySlot)) }
        assertThat(entitySlot.captured.status).isEqualTo("FAILED")
        assertThat(entitySlot.captured.lastError).contains("retry 소진")
    }

    @Test
    fun `(5) incrementRetryCount — retryCount 증가`() {
        val entity = sampleJpaEntity(id = 3L, status = "PENDING", retryCount = 2)
        every { jpaRepository.findById(3L) } returns Optional.of(entity)
        every { jpaRepository.save(any()) } returns sampleJpaEntity(id = 3L, retryCount = 3)

        adapter.incrementRetryCount(3L)

        val entitySlot = slot<NotificationOutboxJpaEntity>()
        verify { jpaRepository.save(capture(entitySlot)) }
        assertThat(entitySlot.captured.retryCount).isEqualTo(3)
    }
}
