package kr.spdaewoon.magicjar.consumer.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.UUID

/**
 * NotificationOutboxRetryWorker 단위 테스트 (spec §2-4 + §11-3 시나리오 3 LOCK).
 *
 * 3 케이스:
 *  (1) PENDING row 재시도 → 성공 시 markSent()
 *  (2) retry_count ≥ MAX_RETRY(5) → markFailed()
 *  (3) PENDING 0건 → 작업 skip
 *
 * FIX C-3 (ADR 0088 합류): outboxRepository 는 direct inject — ObjectProvider 폐기.
 * slackSender 는 webhook URL 미설정 시 옵셔널이므로 ObjectProvider 유지.
 *
 * AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2).
 */
class NotificationOutboxRetryWorkerTest {
    private val outboxRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)

    private val senderProvider = mockk<ObjectProvider<SlackWebhookSender>>()

    private fun newWorker(): NotificationOutboxRetryWorker {
        every { senderProvider.getIfAvailable() } returns slackSender
        return NotificationOutboxRetryWorker(outboxRepository, senderProvider)
    }

    private fun pendingOutbox(
        id: Long,
        retryCount: Int = 0,
        createdAt: Instant = Instant.now().minusSeconds(60),
    ): NotificationOutbox =
        NotificationOutbox(
            id = id,
            eventId = UUID.randomUUID(),
            channel = NotificationChannel.SLACK,
            priority = NotificationPriority.P1,
            payloadJson = """{"eventId":"${UUID.randomUUID()}","eventType":"ORDER_FILLED","userId":"U1","reason":"체결","meta":{}}""",
            status = NotificationStatus.PENDING,
            retryCount = retryCount,
            createdAt = createdAt,
        )

    @Test
    fun `(1) PENDING row 재시도 성공 — markSent 호출`() {
        val outbox = pendingOutbox(id = 1L, retryCount = 1)
        every { outboxRepository.findPending(any()) } returns listOf(outbox)
        every { slackSender.sendOutbox(outbox) } returns true
        every { outboxRepository.markSent(1L) } returns outbox.copy(status = NotificationStatus.SENT)

        val worker = newWorker()
        worker.retryPending()

        verify { outboxRepository.markSent(1L) }
        verify(exactly = 0) { outboxRepository.markFailed(any(), any()) }
    }

    @Test
    fun `(2) retry_count 4 → 실패 시 incrementRetryCount (MAX_RETRY 미달)`() {
        val outbox = pendingOutbox(id = 2L, retryCount = 4)
        every { outboxRepository.findPending(any()) } returns listOf(outbox)
        every { slackSender.sendOutbox(outbox) } returns false

        val worker = newWorker()
        worker.retryPending()

        // retryCount 4 + 1 = 5 == MAX_RETRY → markFailed
        verify { outboxRepository.markFailed(eq(2L), any()) }
    }

    @Test
    fun `(3) PENDING 0건 — 작업 skip findPending 이후 markSent 호출 없음`() {
        every { outboxRepository.findPending(any()) } returns emptyList()

        val worker = newWorker()
        worker.retryPending()

        verify(exactly = 0) { slackSender.sendOutbox(any()) }
        verify(exactly = 0) { outboxRepository.markSent(any()) }
    }

    @Test
    fun `(4) 방금 생성된 PENDING row는 즉시 발송 경로와 중복되지 않도록 유예한다`() {
        val outbox = pendingOutbox(id = 3L, createdAt = Instant.now())
        every { outboxRepository.findPending(any()) } returns listOf(outbox)

        val worker = newWorker()
        worker.retryPending()

        verify(exactly = 0) { slackSender.sendOutbox(any()) }
        verify(exactly = 0) { outboxRepository.markSent(any()) }
    }
}
