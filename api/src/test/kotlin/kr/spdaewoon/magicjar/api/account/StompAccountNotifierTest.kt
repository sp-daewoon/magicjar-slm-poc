package kr.spdaewoon.magicjar.api.account

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.messaging.AccountBalanceChangedStompPayload
import kr.spdaewoon.magicjar.api.messaging.StompAccountNotifier
import kr.spdaewoon.magicjar.api.messaging.StompDestinations
import kr.spdaewoon.magicjar.api.messaging.StompEventPersister
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

/**
 * StompAccountNotifierTest — Kafka `account.balance.changed.v1` → STOMP `/topic/account.balance.{userId}` fanout 검증.
 *
 * Polling → STOMP push 전환 (2026-05-20) — backend 출구점 단위 검증.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합.
 */
class StompAccountNotifierTest {
    private val messagingTemplate: org.springframework.messaging.simp.SimpMessagingTemplate =
        Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate::class.java)
    private val objectMapper = ObjectMapper()
    private val stompEventRepository: StompEventRepository = Mockito.mock(StompEventRepository::class.java)
    private val stompEventPersister = StompEventPersister(stompEventRepository, objectMapper, enabled = true)
    private val sut = StompAccountNotifier(messagingTemplate, objectMapper, stompEventPersister)

    private fun makeRecord(
        userId: String,
        accountType: String = "REAL",
        reason: String = "ORDER_FILLED",
    ): ConsumerRecord<String, String> {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "userId" to userId,
                    "accountType" to accountType,
                    "reason" to reason,
                    "triggerKisOrderNo" to "0000032846",
                    "occurredAt" to "2026-05-20T15:11:55Z",
                ),
            )
        return ConsumerRecord(KafkaTopics.Order.ACCOUNT_BALANCE_CHANGED, 0, 0L, userId, payload)
    }

    @Test
    fun `@KafkaListener 수신 → STOMP fanout payload (accountType + reason + occurredAt) 정합`() {
        val userId = "74683223"
        val payloadCaptor = ArgumentCaptor.forClass(AccountBalanceChangedStompPayload::class.java)

        sut.onAccountBalanceChanged(makeRecord(userId))

        verify(messagingTemplate).convertAndSend(eq(StompDestinations.accountBalanceUser(userId)), payloadCaptor.capture())
        val captured = payloadCaptor.value
        assert(captured.accountType == "REAL") { "accountType=REAL 예상, 실제=${captured.accountType}" }
        assert(captured.reason == "ORDER_FILLED") { "reason=ORDER_FILLED 예상, 실제=${captured.reason}" }
        assert(captured.occurredAt == "2026-05-20T15:11:55Z") { "occurredAt 정합 깨짐: ${captured.occurredAt}" }
    }

    @Test
    fun `MOCK accountType + ORDER_CANCELLED reason 정합 fanout`() {
        val userId = "50000000"
        val payloadCaptor = ArgumentCaptor.forClass(AccountBalanceChangedStompPayload::class.java)

        sut.onAccountBalanceChanged(makeRecord(userId, accountType = "MOCK", reason = "ORDER_CANCELLED"))

        verify(messagingTemplate).convertAndSend(eq(StompDestinations.accountBalanceUser(userId)), payloadCaptor.capture())
        val captured = payloadCaptor.value
        assert(captured.accountType == "MOCK") { "accountType=MOCK 예상, 실제=${captured.accountType}" }
        assert(captured.reason == "ORDER_CANCELLED") { "reason=ORDER_CANCELLED 예상, 실제=${captured.reason}" }
    }

    @Test
    fun `userId null payload → STOMP 발행 skip (손실 허용)`() {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "accountType" to "REAL",
                    "reason" to "ORDER_FILLED",
                    "occurredAt" to "2026-05-20T15:11:55Z",
                ),
            )
        val record = ConsumerRecord(KafkaTopics.Order.ACCOUNT_BALANCE_CHANGED, 0, 0L, "key", payload)

        sut.onAccountBalanceChanged(record)

        verify(messagingTemplate, Mockito.never()).convertAndSend(any<String>(), any<Any>())
    }
}
