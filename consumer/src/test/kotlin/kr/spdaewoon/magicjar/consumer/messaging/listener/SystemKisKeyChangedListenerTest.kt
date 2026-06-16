package kr.spdaewoon.magicjar.consumer.messaging.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.support.Acknowledgment

/**
 * SystemKisKeyChangedListener 단위 테스트 (ADR 0074 Phase 3 검증 의무 §3-4-1).
 *
 * 검증 항목:
 * 1. REAL 키 변경 이벤트 수신 → Redis invalidate + reconnectGracefully 호출
 * 2. MOCK 키 변경 이벤트 수신 → Redis invalidate + reconnectGracefully 미호출
 * 3. 파싱 실패 → ack 즉시 처리 + reconnect 미호출
 *
 * AI 호출 0 — 룰 5 + ADR 0074 §11-2 정합.
 */
class SystemKisKeyChangedListenerTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kisWebSocketBridge = mockk<KisWebSocketBridge>(relaxed = true)
    private val ack = mockk<Acknowledgment>(relaxed = true)

    private lateinit var listener: SystemKisKeyChangedListener

    @BeforeEach
    fun setUp() {
        listener =
            SystemKisKeyChangedListener(
                objectMapper = objectMapper,
                redisTemplate = redisTemplate,
                kisWebSocketBridge = kisWebSocketBridge,
            )
        // Redis hasKey default: lock 존재하는 정상 시나리오
        every { redisTemplate.hasKey(any()) } returns true
        // Redis delete default: 성공
        every { redisTemplate.delete(any<String>()) } returns true
        // KisWebSocketBridge.reconnectGracefully: 정상 호출 허용
        justRun { kisWebSocketBridge.reconnectGracefully(any()) }
    }

    @Test
    fun `REAL 키 변경 이벤트 수신 시 Redis invalidate 후 reconnectGracefully 호출`() {
        // given
        val payload =
            """
            {
              "eventId": "evt-001",
              "userId": "user001",
              "accountType": "REAL",
              "occurredAt": "2026-05-15T01:00:00Z",
              "schemaVersion": 1
            }
            """.trimIndent()
        val record = ConsumerRecord(KafkaTopics.System.KIS_KEY_CHANGED, 0, 0L, "user001", payload)

        // when
        listener.onMessage(record, ack)

        // then
        // Redis cache invalidate — tokenP + approval_key 2건 DEL 의무
        verify { redisTemplate.delete("kis:oauth:token:user:user001:account:REAL") }
        verify { redisTemplate.delete("kis:ws:approval:user:user001:account:REAL") }
        // WS reconnect 호출 의무
        verify { kisWebSocketBridge.reconnectGracefully("user001") }
        // ack 처리 의무
        verify { ack.acknowledge() }
    }

    @Test
    fun `MOCK 키 변경 이벤트 수신 시 Redis invalidate 후 reconnectGracefully 미호출`() {
        // given
        val payload =
            """
            {
              "eventId": "evt-002",
              "userId": "user001",
              "accountType": "MOCK",
              "occurredAt": "2026-05-15T01:01:00Z",
              "schemaVersion": 1
            }
            """.trimIndent()
        val record = ConsumerRecord(KafkaTopics.System.KIS_KEY_CHANGED, 0, 1L, "user001", payload)

        // when
        listener.onMessage(record, ack)

        // then
        // Redis cache invalidate — MOCK tokenP + approval_key 2건 DEL
        verify { redisTemplate.delete("kis:oauth:token:user:user001:account:MOCK") }
        verify { redisTemplate.delete("kis:ws:approval:user:user001:account:MOCK") }
        // MOCK은 WS 미사용 — reconnectGracefully 호출 금지
        verify(exactly = 0) { kisWebSocketBridge.reconnectGracefully(any()) }
        // ack 처리 의무
        verify { ack.acknowledge() }
    }

    @Test
    fun `JSON 파싱 실패 시 ack만 처리하고 reconnectGracefully 미호출`() {
        // given: 잘못된 JSON 페이로드
        val payload = """{ invalid json """
        val record = ConsumerRecord(KafkaTopics.System.KIS_KEY_CHANGED, 0, 2L, "user001", payload)

        // when
        listener.onMessage(record, ack)

        // then
        // 파싱 실패 → reconnect 0
        verify(exactly = 0) { kisWebSocketBridge.reconnectGracefully(any()) }
        // ack는 처리 의무 (dead letter 미지원 — parse 실패 스킵)
        verify { ack.acknowledge() }
    }
}
