package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxStatus
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * OrderOutboxPublisherWorker 단위 테스트 (ADR 0084 P1-1 + spec §2-5).
 *
 * 4 케이스:
 *  (1) PENDING row publish 성공 → markPublished 호출 · failureAlert 0 · KillSwitch 0
 *  (2) publish 실패 1회 (attempt 0→1, MAX_ATTEMPT=3 미달) → recordAttemptFailure 호출
 *  (3) publish 실패 누적 (attempt 2→3, MAX_ATTEMPT 도달) → markFailed + 알림 INSERT + KillSwitch engage
 *  (4) PENDING 0건 → kafkaTemplate.send 0회
 *
 * FIX C-3 (ADR 0088 합류 후): repository/port 는 direct inject. ObjectProvider 폐기.
 *  → (5) "OrderEventOutboxRepository 미등록 graceful skip" 케이스 폐기
 *    (persistence-shared 어댑터 항상 available 보장 → Spring DI fail-fast 정상).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §1-2).
 */
class OrderOutboxPublisherWorkerTest {
    private val outboxRepository = mockk<OrderEventOutboxRepository>(relaxed = true)
    private val notificationRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val killSwitchPort = mockk<KillSwitchTogglePort>(relaxed = true)
    private val killSwitchProvider = mockk<ObjectProvider<KillSwitchTogglePort>>()

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private fun newWorker(): OrderOutboxPublisherWorker {
        every { killSwitchProvider.getIfAvailable() } returns killSwitchPort
        return OrderOutboxPublisherWorker(
            outboxRepository = outboxRepository,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            notificationOutboxRepository = notificationRepository,
            killSwitchTogglePortProvider = killSwitchProvider,
        )
    }

    private fun pendingOutbox(
        id: Long,
        attemptCount: Int = 0,
        eventType: OrderOutboxEventType = OrderOutboxEventType.ORDER_REQUESTED,
        topic: String = KafkaTopics.Order.REQUESTED,
        userId: String = "U1",
    ): OrderEventOutbox =
        OrderEventOutbox(
            id = id,
            eventId = UUID.randomUUID(),
            eventType = eventType,
            topic = topic,
            partitionKey = userId,
            payloadJson = """{"orderId":$id,"userId":"$userId"}""",
            status = OrderOutboxStatus.PENDING,
            attemptCount = attemptCount,
            createdAt = Instant.now(),
        )

    @Test
    fun `(1) PENDING row publish 성공 — markPublished 호출`() {
        val outbox = pendingOutbox(id = 1L)
        every { outboxRepository.findPending(any()) } returns listOf(outbox)
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        val worker = newWorker()
        worker.drainPending()

        verify { kafkaTemplate.send(KafkaTopics.Order.REQUESTED, "U1", outbox.payloadJson) }
        verify { outboxRepository.markPublished(1L) }
        verify(exactly = 0) { outboxRepository.markFailed(any(), any()) }
        verify(exactly = 0) { outboxRepository.recordAttemptFailure(any(), any()) }
        verify(exactly = 0) { notificationRepository.save(any()) }
        verify(exactly = 0) { killSwitchPort.engage(any(), any()) }
    }

    @Test
    fun `(2) publish 실패 1회 — attempt 0→1 (MAX_ATTEMPT 미달) recordAttemptFailure 호출`() {
        val outbox = pendingOutbox(id = 2L, attemptCount = 0)
        every { outboxRepository.findPending(any()) } returns listOf(outbox)
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
            CompletableFuture.failedFuture(RuntimeException("kafka broker down"))

        val worker = newWorker()
        worker.drainPending()

        verify(exactly = 0) { outboxRepository.markFailed(any(), any()) }
        verify { outboxRepository.recordAttemptFailure(eq(2L), any()) }
        verify(exactly = 0) { notificationRepository.save(any()) }
        verify(exactly = 0) { killSwitchPort.engage(any(), any()) }
    }

    @Test
    fun `(3) publish 실패 누적 — attempt 2→3 MAX_ATTEMPT 도달 → markFailed + 알림 + KillSwitch`() {
        val outbox = pendingOutbox(id = 3L, attemptCount = 2) // 다음 시도 시 newAttempt=3 == MAX_ATTEMPT
        every { outboxRepository.findPending(any()) } returns listOf(outbox)
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
            CompletableFuture.failedFuture(RuntimeException("broker timeout"))

        val notificationSlot = slot<NotificationOutbox>()
        every { notificationRepository.save(capture(notificationSlot)) } answers { firstArg() }

        val worker = newWorker()
        worker.drainPending()

        verify { outboxRepository.markFailed(eq(3L), any()) }
        verify(exactly = 0) { outboxRepository.recordAttemptFailure(any(), any()) }
        verify { notificationRepository.save(any()) }
        verify { killSwitchPort.engage("U1", OrderOutboxPublisherWorker.KILLSWITCH_REASON) }

        // 알림 priority P0 검증
        assertThat(notificationSlot.captured.priority.name).isEqualTo("P0")
        assertThat(notificationSlot.captured.payloadJson).contains("ORDER_OUTBOX_DRAIN_FAILED")
        assertThat(notificationSlot.captured.payloadJson).contains(KafkaTopics.Order.REQUESTED)
    }

    @Test
    fun `(4) PENDING 0건 — kafkaTemplate send 0회`() {
        every { outboxRepository.findPending(any()) } returns emptyList()

        val worker = newWorker()
        worker.drainPending()

        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
        verify(exactly = 0) { outboxRepository.markPublished(any()) }
    }
}
