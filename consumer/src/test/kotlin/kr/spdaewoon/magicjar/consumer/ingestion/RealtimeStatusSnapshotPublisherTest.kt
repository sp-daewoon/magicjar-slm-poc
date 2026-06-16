package kr.spdaewoon.magicjar.consumer.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.consumer.ws.WsStatusSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * RealtimeStatusSnapshotPublisher 단위 테스트 — Redis `ops:realtime:status` 직렬화 정합.
 *
 * 검증:
 * - WS·슬롯·체결가/호가 구독·체결통보 스냅샷이 단일 페이로드로 합성된다.
 * - 빈 부재(qa fixture)에서 graceful — bridge 부재 시 updatedAt만 채운 페이로드.
 * - publish 실패가 스케줄러 cascade를 막지 않는다(runCatching 흡수).
 */
class RealtimeStatusSnapshotPublisherTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Suppress("UNCHECKED_CAST")
    private fun <T> provider(value: T?): ObjectProvider<T> =
        mockk<ObjectProvider<T>>(relaxed = true).also { every { it.ifAvailable } returns value }

    @Test
    fun `WS·슬롯·구독·체결통보 합성 후 admin realtime-status 발행`() {
        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redis.opsForValue() } returns valueOps
        val jsonSlot = slot<String>()
        every { valueOps.set("ops:realtime:status", capture(jsonSlot), any<Duration>()) } returns Unit

        val bridge =
            mockk<KisWebSocketBridge> {
                every { wsStatusSnapshot() } returns
                    WsStatusSnapshot(
                        connected = true,
                        sessionCid = "cid-1",
                        reconnects24h = 3,
                        lastConnectedAtMs = 1_700_000_000_000L,
                        executionSubscribed = true,
                        executionMode = "MOCK",
                        executionTrId = "H0STCNI9",
                        executionUserIdMasked = "7468****",
                    )
                // ADR 0124 — 체결가 라벨은 단일 채널 H0UNCNT0.
                every { activeTickTrId() } returns "H0UNCNT0"
                every { subscribedMarketStatusCodes() } returns emptySet()
            }
        val realtimeRouter =
            mockk<RealtimeSubscriptionRouter> {
                every { snapshotDemands() } returns
                    listOf(RealtimeDemandSnapshot("005930", listOf("detail:user001", "strategy:86"), 1_700_000_000_000L))
            }
        val orderbookRouter =
            mockk<OrderbookSubscriptionRouter> {
                every { snapshotDemands() } returns
                    listOf(OrderbookDemandSnapshot("005930", listOf("strategy:86"), 1_700_000_001_000L))
            }
        val ledger =
            mockk<WsSlotBudgetLedger> {
                every { snapshot() } returns
                    WsSlotBudgetLedger.SlotSnapshot(
                        used = 2,
                        max = 40,
                        byTrId = sortedMapOf("H0UNCNT0" to 1, "H0UNASP0" to 1),
                        codesByTrId = sortedMapOf("H0UNCNT0" to listOf("005930"), "H0UNASP0" to listOf("005930")),
                    )
            }

        val publisher =
            RealtimeStatusSnapshotPublisher(
                redisTemplate = redis,
                objectMapper = objectMapper,
                bridgeProvider = provider(bridge),
                realtimeRouterProvider = provider(realtimeRouter),
                orderbookRouterProvider = provider(orderbookRouter),
                slotLedgerProvider = provider(ledger),
            )

        publisher.publishNow()

        verify { valueOps.set("ops:realtime:status", any(), any<Duration>()) }
        val payload = objectMapper.readValue(jsonSlot.captured, RealtimeStatusSnapshot::class.java)
        assertThat(payload.ws.connected).isTrue()
        assertThat(payload.ws.reconnects24h).isEqualTo(3)
        assertThat(payload.slots.used).isEqualTo(2)
        assertThat(payload.slots.byTrId["H0UNCNT0"]).isEqualTo(1)
        // 슬롯 drill-down — codesByTrId 페이로드 흐름 검증.
        assertThat(payload.slots.codesByTrId["H0UNCNT0"]).containsExactly("005930")
        assertThat(payload.subscriptions).hasSize(2)
        // ADR 0124 결정 4 — 체결가 라벨은 활성 채널(H0UNCNT0). 호가는 H0UNASP0.
        assertThat(payload.subscriptions.map { it.trId }).containsExactly("H0UNASP0", "H0UNCNT0")
        assertThat(payload.executionNotice.subscribed).isTrue()
        assertThat(payload.executionNotice.trId).isEqualTo("H0STCNI9")
    }

    @Test
    fun `bridge 부재(fixture) — graceful 빈 페이로드`() {
        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redis.opsForValue() } returns valueOps
        val jsonSlot = slot<String>()
        every { valueOps.set(any(), capture(jsonSlot), any<Duration>()) } returns Unit

        val publisher =
            RealtimeStatusSnapshotPublisher(
                redisTemplate = redis,
                objectMapper = objectMapper,
                bridgeProvider = provider(null),
                realtimeRouterProvider = provider(null),
                orderbookRouterProvider = provider(null),
                slotLedgerProvider = provider(null),
            )

        publisher.publishNow()

        val payload = objectMapper.readValue(jsonSlot.captured, RealtimeStatusSnapshot::class.java)
        assertThat(payload.ws.connected).isFalse()
        assertThat(payload.subscriptions).isEmpty()
        assertThat(payload.updatedAt).isNotBlank()
    }

    @Test
    fun `Redis 발행 실패는 흡수된다(스케줄러 cascade 차단 방지)`() {
        val redis = mockk<StringRedisTemplate>()
        every { redis.opsForValue() } throws RuntimeException("redis down")

        val publisher =
            RealtimeStatusSnapshotPublisher(
                redisTemplate = redis,
                objectMapper = objectMapper,
                bridgeProvider = provider(null),
                realtimeRouterProvider = provider(null),
                orderbookRouterProvider = provider(null),
                slotLedgerProvider = provider(null),
            )

        // 예외 전파 없이 정상 반환해야 한다.
        publisher.publishNow()
    }
}
