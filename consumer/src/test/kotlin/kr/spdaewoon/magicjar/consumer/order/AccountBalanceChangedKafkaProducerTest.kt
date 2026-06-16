package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * AccountBalanceChangedKafkaProducer 단위 테스트.
 *
 * Polling → STOMP push 전환 (2026-05-20) — payload schema · partition key · topic LOCK 검증.
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class AccountBalanceChangedKafkaProducerTest {
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-20T15:11:55Z"), ZoneId.of("Asia/Seoul"))

    private val sut =
        AccountBalanceChangedKafkaProducer(
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            clock = fixedClock,
        )

    @Test
    fun `publish — topic + partition key(userId) + payload schema 정합`() {
        val topicSlot = slot<String>()
        val keySlot = slot<String>()
        val valueSlot = slot<String>()
        every { kafkaTemplate.send(capture(topicSlot), capture(keySlot), capture(valueSlot)) } returns mockk(relaxed = true)

        sut.publish(
            userId = "74683223",
            mode = TradingMode.REAL,
            reason = AccountBalanceChangedKafkaProducer.REASON_FILLED,
            triggerKisOrderNo = "0000032846",
        )

        // topic LOCK
        assertThat(topicSlot.captured).isEqualTo(KafkaTopics.Order.ACCOUNT_BALANCE_CHANGED)
        // partition key = userId (per-user FIFO 보장)
        assertThat(keySlot.captured).isEqualTo("74683223")

        // payload schema LOCK — JSON 역직렬화 후 5 필드 검증
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(valueSlot.captured, Map::class.java) as Map<String, Any?>
        assertThat(payload["userId"]).isEqualTo("74683223")
        assertThat(payload["accountType"]).isEqualTo("REAL")
        assertThat(payload["reason"]).isEqualTo("ORDER_FILLED")
        assertThat(payload["triggerKisOrderNo"]).isEqualTo("0000032846")
        // occurredAt: clock fixed → 결정적 검증
        assertThat(payload["occurredAt"]).isEqualTo("2026-05-20T15:11:55Z")

        verify(exactly = 1) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
    }

    @Test
    fun `publish — MOCK 모드 accountType MOCK 직렬화`() {
        val valueSlot = slot<String>()
        every { kafkaTemplate.send(any<String>(), any<String>(), capture(valueSlot)) } returns mockk(relaxed = true)

        sut.publish(
            userId = "12345678",
            mode = TradingMode.MOCK,
            reason = AccountBalanceChangedKafkaProducer.REASON_CANCELLED,
            triggerKisOrderNo = null,
        )

        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(valueSlot.captured, Map::class.java) as Map<String, Any?>
        assertThat(payload["accountType"]).isEqualTo("MOCK")
        assertThat(payload["reason"]).isEqualTo("ORDER_CANCELLED")
        // triggerKisOrderNo nullable — JSON null 허용
        assertThat(payload.containsKey("triggerKisOrderNo")).isTrue
        assertThat(payload["triggerKisOrderNo"]).isNull()
    }

    @Test
    fun `publish — Kafka send 실패 시 graceful swallow (예외 propagate 안 함)`() {
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } throws
            RuntimeException("kafka down")

        // 예외 propagate 0 — 자본 흐름 본체는 이미 완료된 상태이므로 push 누락은 graceful.
        sut.publish(
            userId = "74683223",
            mode = TradingMode.REAL,
            reason = AccountBalanceChangedKafkaProducer.REASON_REJECTED,
            triggerKisOrderNo = "0000099999",
        )

        verify(exactly = 1) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
    }
}
