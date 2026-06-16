package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * P2 — Kafka system.event.v1 → STOMP fanout 분기 검증.
 *
 * 5 case:
 * 1. userId != null → /topic/system.event.user.{userId} (user-specific)
 * 2. userId == null → /topic/system.event.broadcast (system-wide)
 * 3. 잘못된 payload → log.error + skip (listener thread 안 죽임)
 * 4. P2.1 ★★ #1 — userId != null → StompEventPersister.persist 호출 (backfill 영속)
 * 5. P2.1 ★★ #1 — userId == null (broadcast) → persist skip (multi-tenant key 부재)
 *
 * 본 test는 mockk 기반 — plan 본문 정확 정합 (T-20260520 system-event-log-p2 Task 1 Step 1).
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class SystemEventStompNotifierTest {
    private val stomp = mockk<SimpMessagingTemplate>(relaxed = true)
    private val stompEventPersister = mockk<StompEventPersister>(relaxed = true)
    private val objectMapper =
        ObjectMapper().apply {
            registerKotlinModule()
            registerModule(JavaTimeModule())
        }
    private val sut = SystemEventStompNotifier(stomp, objectMapper, stompEventPersister)

    @Test
    fun `userId 있는 event — user-specific topic 으로 fanout`() {
        val payload =
            """{"category":"RECONCILE","severity":"WARN","actor":"OrderReconciliationWorker","action":"RECONCILE_FALLBACK_RESOLVED","userId":"74683223","correlationId":null,"detailJson":"{\"orderId\":5}","occurredAt":"2026-05-20T15:00:00Z"}"""
        val record = ConsumerRecord(KafkaTopics.System.EVENT, 0, 0L, "74683223", payload)

        sut.onMessage(record)

        verify { stomp.convertAndSend(StompDestinations.systemEventUser("74683223"), any<Any>()) }
    }

    @Test
    fun `userId null event — broadcast topic 으로 fanout`() {
        val payload =
            """{"category":"WEBSOCKET","severity":"WARN","actor":"KisWebSocketBridge","action":"WS_DISCONNECTED","userId":null,"correlationId":"abc-123","detailJson":"{\"reason\":\"timeout\"}","occurredAt":"2026-05-20T15:00:00Z"}"""
        val record = ConsumerRecord(KafkaTopics.System.EVENT, 0, 0L, "broadcast", payload)

        sut.onMessage(record)

        verify { stomp.convertAndSend(StompDestinations.SYSTEM_EVENT_BROADCAST, any<Any>()) }
    }

    @Test
    fun `잘못된 payload — log error + skip (listener thread 안 죽임)`() {
        val record = ConsumerRecord(KafkaTopics.System.EVENT, 0, 0L, "broadcast", "{invalid json")

        sut.onMessage(record) // no throw

        verify(exactly = 0) { stomp.convertAndSend(any<String>(), any<Any>()) }
        verify(exactly = 0) {
            stompEventPersister.persist(any<String>(), any(), any(), any())
        }
    }

    @Test
    fun `P2-1 — userId 있는 event 는 StompEventPersister persist 호출 (backfill 영속)`() {
        val payload =
            """{"category":"RECONCILE","severity":"CRITICAL","actor":"OrderReconciliationWorker","action":"RECONCILE_FALLBACK_FIRED","userId":"74683223","correlationId":null,"detailJson":"{\"orderId\":5}","occurredAt":"2026-05-20T15:00:00Z"}"""
        val record = ConsumerRecord(KafkaTopics.System.EVENT, 0, 0L, "74683223", payload)

        sut.onMessage(record)

        verify(exactly = 1) {
            stompEventPersister.persist(
                userId = "74683223",
                channel = "system-event",
                eventType = "RECONCILE_FALLBACK_FIRED",
                payload = any(),
            )
        }
    }

    @Test
    fun `P2-1 — userId null (broadcast) 는 persist skip (multi-tenant key 부재)`() {
        val payload =
            """{"category":"WEBSOCKET","severity":"WARN","actor":"KisWebSocketBridge","action":"WS_DISCONNECTED","userId":null,"correlationId":"abc-123","detailJson":"{\"reason\":\"timeout\"}","occurredAt":"2026-05-20T15:00:00Z"}"""
        val record = ConsumerRecord(KafkaTopics.System.EVENT, 0, 0L, "broadcast", payload)

        sut.onMessage(record)

        verify { stomp.convertAndSend(StompDestinations.SYSTEM_EVENT_BROADCAST, any<Any>()) }
        verify(exactly = 0) {
            stompEventPersister.persist(any<String>(), any(), any(), any())
        }
    }
}
