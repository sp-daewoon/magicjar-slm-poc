package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * OrderDltMonitorListener 단위 테스트 (ADR 0084 P0-4).
 *
 * 검증 매트릭스:
 *  (a) DLT 수신 → outbox INSERT (channel=SLACK, priority=P0, eventType=DLT_RECEIVED)
 *  (b) Slack 즉시 발송 시도 (sendOutbox 호출 + markSent on success)
 *  (c) outbox repository 미설정 시 → graceful direct Slack
 *  (d) Slack 발송 실패 시 → incrementRetryCount + PENDING 유지
 *  (e) payload userId 추출 best-effort (JSON parse fail 시 "unknown")
 *  (f) 4 DLT topic 각각 listener 메서드 routing 정합
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-8).
 */
class OrderDltMonitorListenerTest {
    private val outboxRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val outboxProvider =
        mockk<ObjectProvider<NotificationOutboxRepository>>().also {
            every { it.getIfAvailable() } returns outboxRepository
        }
    private val slackProvider =
        mockk<ObjectProvider<SlackWebhookSender>>().also {
            every { it.getIfAvailable() } returns slackSender
        }

    private val listener =
        OrderDltMonitorListener(
            outboxRepositoryProvider = outboxProvider,
            slackSenderProvider = slackProvider,
            objectMapper = objectMapper,
        )

    @Test
    fun `DLT 수신 시 outbox INSERT (P0_DLT_RECEIVED) + Slack 즉시 발송`() {
        val payload = """{"userId":"user-123","signalId":"sig-1","code":"005930","side":"BUY"}"""
        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_INTENT, 0, 100L, "user-123", payload)

        val outboxSlot = slot<NotificationOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            outboxSlot.captured.copy(id = 99L)
        }
        every { slackSender.sendOutbox(any()) } returns true

        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_INTENT)

        verify { outboxRepository.save(any()) }
        verify { slackSender.sendOutbox(any()) }
        verify { outboxRepository.markSent(99L) }

        val saved = outboxSlot.captured
        assertThat(saved.channel).isEqualTo(NotificationChannel.SLACK)
        assertThat(saved.priority).isEqualTo(NotificationPriority.P0)
        assertThat(saved.status).isEqualTo(NotificationStatus.PENDING)
        assertThat(saved.payloadJson).contains("DLT_RECEIVED")
        assertThat(saved.payloadJson).contains(KafkaTopics.Dlt.ORDER_INTENT)
        assertThat(saved.payloadJson).contains("user-123")
    }

    @Test
    fun `outbox repository 미설정 시 graceful direct Slack fallback`() {
        every { outboxProvider.getIfAvailable() } returns null

        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_FILLED, 0, 50L, "k", """{"userId":"u1"}""")

        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_FILLED)

        verify(exactly = 0) { outboxRepository.save(any()) }
        verify { slackSender.sendDirect(any()) }
    }

    @Test
    fun `Slack 발송 실패 시 incrementRetryCount + PENDING 유지`() {
        every { outboxProvider.getIfAvailable() } returns outboxRepository

        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_REJECTED, 0, 70L, "u1", """{"userId":"u1"}""")
        every { outboxRepository.save(any()) } answers {
            firstArg<NotificationOutbox>().copy(id = 7L)
        }
        every { slackSender.sendOutbox(any()) } returns false

        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_REJECTED)

        verify { slackSender.sendOutbox(any()) }
        verify { outboxRepository.incrementRetryCount(7L) }
        verify(exactly = 0) { outboxRepository.markSent(any()) }
    }

    @Test
    fun `Slack 발송 예외 시 incrementRetryCount`() {
        every { outboxProvider.getIfAvailable() } returns outboxRepository

        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_REQUESTED, 0, 11L, "u1", """{"userId":"u1"}""")
        every { outboxRepository.save(any()) } answers {
            firstArg<NotificationOutbox>().copy(id = 3L)
        }
        every { slackSender.sendOutbox(any()) } throws RuntimeException("slack down")

        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_REQUESTED)

        verify { outboxRepository.incrementRetryCount(3L) }
    }

    @Test
    fun `payload JSON parse 실패 시 userId unknown으로 처리`() {
        every { outboxProvider.getIfAvailable() } returns outboxRepository

        val invalidPayload = "this is not json"
        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_INTENT, 0, 200L, null, invalidPayload)

        val outboxSlot = slot<NotificationOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers { outboxSlot.captured.copy(id = 1L) }
        every { slackSender.sendOutbox(any()) } returns true

        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_INTENT)

        verify { outboxRepository.save(any()) }
        assertThat(outboxSlot.captured.payloadJson).contains("\"userId\":\"unknown\"")
    }

    @Test
    fun `4 DLT topic 모두 listener method routing 정합`() {
        // onOrderIntentDlt
        val intentRecord = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_INTENT, 0, 1L, "k", """{"userId":"u"}""")
        every { outboxRepository.save(any()) } answers { firstArg<NotificationOutbox>().copy(id = 10L) }
        every { slackSender.sendOutbox(any()) } returns true

        listener.onOrderIntentDlt(intentRecord)
        listener.onOrderRequestedDlt(intentRecord)
        listener.onOrderRejectedDlt(intentRecord)
        listener.onOrderFilledDlt(intentRecord)

        verify(exactly = 4) { outboxRepository.save(any()) }
    }

    @Test
    fun `listener 자체 예외 발생 시 swallow (무한 loop 회피)`() {
        every { outboxProvider.getIfAvailable() } returns outboxRepository
        every { outboxRepository.save(any()) } throws RuntimeException("DB down")

        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_INTENT, 0, 1L, "k", """{"userId":"u"}""")

        // 예외 throw 시 시 테스트 실패 — handleDlt catch-all 검증
        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_INTENT)
        // 정상 종료 — 예외 swallow 정합
    }

    @Test
    fun `empty payload 시에도 outbox INSERT 발사`() {
        every { outboxProvider.getIfAvailable() } returns outboxRepository
        val record = ConsumerRecord<String, String>(KafkaTopics.Dlt.ORDER_INTENT, 0, 1L, "k", null)
        every { outboxRepository.save(any()) } answers { firstArg<NotificationOutbox>().copy(id = 1L) }
        every { slackSender.sendOutbox(any()) } returns true

        listener.handleDlt(record, KafkaTopics.Dlt.ORDER_INTENT)

        verify { outboxRepository.save(any()) }
    }
}
