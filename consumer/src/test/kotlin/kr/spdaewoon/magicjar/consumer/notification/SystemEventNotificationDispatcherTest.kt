package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

class SystemEventNotificationDispatcherTest {
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val outboxRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val rateLimiter = mockk<NotificationRateLimiterPort>()
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)

    private val outboxProvider = mockk<ObjectProvider<NotificationOutboxRepository>>()
    private val rateLimiterProvider = mockk<ObjectProvider<NotificationRateLimiterPort>>()
    private val slackSenderProvider = mockk<ObjectProvider<SlackWebhookSender>>()

    private val dispatcher =
        run {
            every { outboxProvider.getIfAvailable() } returns outboxRepository
            every { rateLimiterProvider.getIfAvailable() } returns rateLimiter
            every { slackSenderProvider.getIfAvailable() } returns slackSender
            every { rateLimiter.shouldEmit(any(), any(), any(), any()) } returns true
            every { outboxRepository.save(any()) } answers {
                val arg = it.invocation.args[0] as NotificationOutbox
                arg.copy(id = 200L)
            }
            SystemEventNotificationDispatcher(outboxProvider, rateLimiterProvider, slackSenderProvider, objectMapper)
        }

    @Test
    fun `ERROR system event inserts P0 outbox and sends Slack immediately`() {
        dispatcher.onSystemEvent(record(event(SystemEventSeverity.ERROR)))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `WARN system event uses grouped P2 outbox`() {
        dispatcher.onSystemEvent(record(event(SystemEventSeverity.WARN)))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P2)
        verify(exactly = 0) { slackSender.sendOutbox(any()) }
        verify { rateLimiter.shouldEmit("broadcast", "system.websocket.ws_disconnected", 3, 10) }
    }

    @Test
    fun `INFO system event is not sent to Slack`() {
        dispatcher.onSystemEvent(record(event(SystemEventSeverity.INFO)))

        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `친절도 — KIS_TPS_EXCEEDED 알림 본문에 모듈·원인·조치 가이드 1줄 부착`() {
        val tps =
            event(SystemEventSeverity.WARN).copy(
                category = SystemEventCategory.KIS_RATELIMIT,
                actor = "ConsumerRedisKisRateLimiterAdapter",
                action = "KIS_TPS_EXCEEDED",
                detailJson = """{"tpsExceededCount":12,"module":"consumer"}""",
            )
        dispatcher.onSystemEvent(record(tps))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        val reason = reasonFrom(slot.captured)
        // 기술 1줄 보존 + 사람어 가이드 1줄 add-only
        assertThat(reason).contains("KIS_RATELIMIT/KIS_TPS_EXCEEDED")
        assertThat(reason).contains("모듈: KIS rate limiter")
        assertThat(reason).contains("조치:")
        // 2026-06-10 알람 차등화 — 가이드 본문이 윈도 건수(windowCount) 기반 안내로 갱신됨
        assertThat(reason).contains("windowCount")
    }

    @Test
    fun `친절도 — CANDLE_BACKFILL_FAIL 알림 본문에 백필 모듈·재시도 조치 가이드 부착`() {
        val backfill =
            event(SystemEventSeverity.WARN).copy(
                category = SystemEventCategory.OTHER,
                actor = "CandleBackfillWorker",
                action = "CANDLE_BACKFILL_FAIL",
                detailJson = """{"code":"005930","attempt":1}""",
            )
        dispatcher.onSystemEvent(record(backfill))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        val reason = reasonFrom(slot.captured)
        assertThat(reason).contains("CANDLE_BACKFILL_FAIL")
        assertThat(reason).contains("모듈: 캔들 백필 워커")
        assertThat(reason).contains("자동 재시도")
    }

    @Test
    fun `친절도 — 미등록 action은 가이드 없이 기술 1줄만 (graceful 회귀 보호)`() {
        val unknown =
            event(SystemEventSeverity.WARN).copy(
                category = SystemEventCategory.DB,
                action = "SOME_UNMAPPED_ACTION",
            )
        dispatcher.onSystemEvent(record(unknown))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        val reason = reasonFrom(slot.captured)
        assertThat(reason).contains("DB/SOME_UNMAPPED_ACTION")
        assertThat(reason).doesNotContain("조치:")
    }

    @Suppress("UNCHECKED_CAST")
    private fun reasonFrom(outbox: NotificationOutbox): String {
        val payload = objectMapper.readValue(outbox.payloadJson, Map::class.java) as Map<String, Any?>
        return payload["reason"] as String
    }

    private fun record(event: SystemEvent): ConsumerRecord<String, String> =
        ConsumerRecord(KafkaTopics.System.EVENT, 0, 0L, "broadcast", objectMapper.writeValueAsString(event))

    private fun event(severity: SystemEventSeverity): SystemEvent =
        SystemEvent(
            category = SystemEventCategory.WEBSOCKET,
            severity = severity,
            actor = "KisWebSocketBridge",
            action = "WS_DISCONNECTED",
            userId = null,
            correlationId = "cid-1",
            detailJson = """{"reason":"test"}""",
            occurredAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
}
