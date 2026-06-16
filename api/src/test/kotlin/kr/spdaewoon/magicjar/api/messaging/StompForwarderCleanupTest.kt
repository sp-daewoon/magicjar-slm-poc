package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * StompForwarder stale entry cleanup 검증 — backlog §29 #1·#2 (안정성 Cycle 1).
 *
 * 검증 목표:
 *   1. cleanupStaleEntries() — STALE_THRESHOLD_MS(1h) 이상 oldEntry 제거
 *   2. fresh entry는 보존
 *   3. 상수 산식 정합: STALE=1h, INTERVAL=5min
 */
class StompForwarderCleanupTest {
    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val objectMapper = ObjectMapper()

    /**
     * 검증 1: tick forwarder — stale entry 제거 + fresh entry 보존.
     */
    @Test
    fun `MarketTickStompForwarder cleanupStaleEntries - stale 제거 fresh 보존`() {
        val forwarder = MarketTickStompForwarder(messagingTemplate, objectMapper)
        val now = System.currentTimeMillis()

        // given: stale 3건 (2h ago), fresh 2건 (30min ago)
        forwarder.lastEmitMs["005930"] = now - 2L * 60L * 60L * 1_000L
        forwarder.lastEmitMs["000660"] = now - 2L * 60L * 60L * 1_000L
        forwarder.lastEmitMs["035420"] = now - 90L * 60L * 1_000L // 1.5h ago = stale
        forwarder.lastEmitMs["035720"] = now - 30L * 60L * 1_000L
        forwarder.lastEmitMs["051910"] = now

        // when
        forwarder.cleanupStaleEntries()

        // then
        assertThat(forwarder.lastEmitMs).hasSize(2)
        assertThat(forwarder.lastEmitMs).containsKeys("035720", "051910")
        assertThat(forwarder.lastEmitMs).doesNotContainKeys("005930", "000660", "035420")
    }

    /**
     * 검증 2: orderbook forwarder — 동일 패턴 검증 (헌법 §단일 책임 정합 — 두 forwarder cleanup 동일).
     */
    @Test
    fun `MarketOrderbookStompForwarder cleanupStaleEntries - stale 제거 fresh 보존`() {
        val forwarder = MarketOrderbookStompForwarder(messagingTemplate, objectMapper)
        val now = System.currentTimeMillis()

        // given
        forwarder.lastEmitMs["005930"] = now - 2L * 60L * 60L * 1_000L
        forwarder.lastEmitMs["035720"] = now - 30L * 60L * 1_000L

        // when
        forwarder.cleanupStaleEntries()

        // then
        assertThat(forwarder.lastEmitMs).hasSize(1)
        assertThat(forwarder.lastEmitMs).containsKey("035720")
    }

    /**
     * 검증 3: 빈 map cleanup — exception 없이 no-op.
     */
    @Test
    fun `cleanupStaleEntries - 빈 map no-op`() {
        val tick = MarketTickStompForwarder(messagingTemplate, objectMapper)
        val orderbook = MarketOrderbookStompForwarder(messagingTemplate, objectMapper)

        // when/then — exception 미발생
        tick.cleanupStaleEntries()
        orderbook.cleanupStaleEntries()

        assertThat(tick.lastEmitMs).isEmpty()
        assertThat(orderbook.lastEmitMs).isEmpty()
    }

    /**
     * 검증 4: 상수 산식 정합 — STALE_THRESHOLD_MS=1h, CLEANUP_INTERVAL_MS=5min.
     */
    @Test
    fun `상수 산식 정합`() {
        assertThat(MarketTickStompForwarder.STALE_THRESHOLD_MS).isEqualTo(60L * 60L * 1_000L)
        assertThat(MarketTickStompForwarder.CLEANUP_INTERVAL_MS).isEqualTo(5L * 60L * 1_000L)
        assertThat(MarketOrderbookStompForwarder.STALE_THRESHOLD_MS).isEqualTo(60L * 60L * 1_000L)
        assertThat(MarketOrderbookStompForwarder.CLEANUP_INTERVAL_MS).isEqualTo(5L * 60L * 1_000L)
    }
}
