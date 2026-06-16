package kr.spdaewoon.magicjar.api.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.messaging.OrderStompPayload
import kr.spdaewoon.magicjar.api.messaging.StompDestinations
import kr.spdaewoon.magicjar.api.messaging.StompEventPersister
import kr.spdaewoon.magicjar.api.messaging.StompOrderNotifier
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * StompOrderNotifierTest — ADR 0068 §15-6 LOCK 4 event discriminator 케이스.
 *
 * 4 topic → 4 event type → STOMP 발행 검증
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class StompOrderNotifierTest {
    private val messagingTemplate: SimpMessagingTemplate = Mockito.mock(SimpMessagingTemplate::class.java)
    private val objectMapper = ObjectMapper()

    // ADR 0084 P1-2: StompEventPersister inject — repository mock + enabled=true (테스트 본의는 publish 검증)
    private val stompEventRepository: StompEventRepository = Mockito.mock(StompEventRepository::class.java)
    private val stompEventPersister = StompEventPersister(stompEventRepository, objectMapper, enabled = true)
    private val sut = StompOrderNotifier(messagingTemplate, objectMapper, stompEventPersister)

    private fun makeRecord(
        topic: String,
        userId: String,
        orderId: Long = 1L,
    ): ConsumerRecord<String, String> {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "userId" to userId,
                    "orderId" to orderId,
                    "code" to "005930",
                    "side" to "BUY",
                    "quantity" to 10,
                ),
            )
        return ConsumerRecord(topic, 0, 0L, "key", payload)
    }

    @Test
    fun `order_requested_v1 → REQUESTED event → STOMP 발행`() {
        val topic = KafkaTopics.Order.REQUESTED
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), any<Any>())
    }

    @Test
    fun `order_filled_v1 → FILLED event → STOMP 발행`() {
        val topic = KafkaTopics.Order.FILLED
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), any<Any>())
    }

    @Test
    fun `order_rejected_v1 → REJECTED event → STOMP 발행`() {
        val topic = KafkaTopics.Order.REJECTED
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), any<Any>())
    }

    @Test
    fun `order_cancelled_v1 → CANCELLED event → STOMP 발행`() {
        val topic = KafkaTopics.Order.CANCELLED
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), any<Any>())
    }

    // ─────────────────────────────────────────────────────────────────────
    // hotfix(2026-05-20): FILLED qty/executedQty 매핑 회귀 가드
    // 사용자 dogfooding "[누락] 005930 0주 @ 273,500원" toast 결함 대응.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `FILLED — publisher contract 정합 (executedQty + quantity key) — qty·executedQty 정상 매핑`() {
        val topic = KafkaTopics.Order.FILLED
        val userId = "user1"
        // OrderFilledKafkaProducer가 publish하는 STOMP contract 정합 payload
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "userId" to userId,
                    "orderId" to 2,
                    "code" to "005930",
                    "side" to "BUY",
                    "quantity" to 10,
                    "executedQty" to 10,
                    "executedPrice" to "273500",
                    "partial" to false,
                ),
            )
        val record = ConsumerRecord<String, String>(topic, 0, 0L, "key", payload)

        sut.onOrderEvent(record, topic)

        val captor = ArgumentCaptor.forClass(OrderStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), captor.capture())
        val sent = captor.value
        assertThat(sent.event).isEqualTo("FILLED")
        assertThat(sent.qty).isEqualTo(10)
        assertThat(sent.executedQty).isEqualTo(10)
        assertThat(sent.executedPrice).isEqualTo("273500")
    }

    @Test
    fun `FILLED — 옛 publisher contract (executedQuantity key) defensive fallback`() {
        // outbox replay·in-flight Kafka msg lag 시 옛 payload key 잔존 case
        val topic = KafkaTopics.Order.FILLED
        val userId = "user1"
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "userId" to userId,
                    "orderId" to 2,
                    "code" to "005930",
                    "side" to "BUY",
                    "executedQuantity" to 7, // 옛 key
                    "executedPrice" to "273500",
                ),
            )
        val record = ConsumerRecord<String, String>(topic, 0, 0L, "key", payload)

        sut.onOrderEvent(record, topic)

        val captor = ArgumentCaptor.forClass(OrderStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), captor.capture())
        assertThat(captor.value.executedQty).isEqualTo(7)
    }

    @Test
    fun `REJECTED — 옛 publisher contract (reason key) defensive fallback → rejectReason 매핑`() {
        val topic = KafkaTopics.Order.REJECTED
        val userId = "user1"
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "userId" to userId,
                    "orderId" to 3,
                    "code" to "005930",
                    "side" to "BUY",
                    "reason" to "REAL_GATE_DENIED:test", // 옛 key
                ),
            )
        val record = ConsumerRecord<String, String>(topic, 0, 0L, "key", payload)

        sut.onOrderEvent(record, topic)

        val captor = ArgumentCaptor.forClass(OrderStompPayload::class.java)
        verify(messagingTemplate).convertAndSend(eq(StompDestinations.orderUser(userId)), captor.capture())
        assertThat(captor.value.rejectReason).isEqualTo("REAL_GATE_DENIED:test")
    }
}
