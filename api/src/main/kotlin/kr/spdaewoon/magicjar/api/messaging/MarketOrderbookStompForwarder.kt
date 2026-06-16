package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Kafka market.orderbook.v1 → STOMP /topic/market.orderbook.{code} 포워더 (ADR 0048 §5-1).
 *
 * 200ms throttle (호가 메시지 빈도가 높아 frontend 부담 차단).
 *
 * cleanup (backlog §29 #2, 안정성 Cycle 1):
 *   5분 마다 stale entry(lastEmit < now - 1h) 제거. tick forwarder와 동일 패턴 정합.
 *   상한 상 매우 작아(50 종목 × 80 byte ≈ 4 KB) heap risk는 0이나 명시적 정책으로 정합.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class MarketOrderbookStompForwarder(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketOrderbookStompForwarder::class.java)

    // 200ms throttle: code → lastEmitMs
    internal val lastEmitMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val throttleMs = 200L

    companion object {
        /** stale entry 임계 (1시간). 마지막 호가가 1시간 이상 없으면 cleanup 대상. */
        const val STALE_THRESHOLD_MS = 60L * 60L * 1_000L

        /** cleanup 주기 (5분). @Scheduled fixedDelay = 직전 실행 완료 후 5분. */
        const val CLEANUP_INTERVAL_MS = 5L * 60L * 1_000L
    }

    @KafkaListener(
        topics = [KafkaTopics.Market.ORDERBOOK],
        groupId = "magicjar-api-stomp-orderbook",
    )
    fun onOrderbookMessage(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            // 200ms throttle
            val now = System.currentTimeMillis()
            val last = lastEmitMs.getOrDefault(code, 0L)
            if (now - last < throttleMs) {
                log.debug("orderbook throttle skip: code={}", code)
                return
            }
            lastEmitMs[code] = now

            messagingTemplate.convertAndSend(StompDestinations.marketOrderbook(code), payload)
            log.debug("STOMP /topic/market.orderbook.{} 발행", code)
        } catch (e: Exception) {
            log.error("market.orderbook STOMP 포워드 실패: offset={} err={}", record.offset(), e.message)
        }
    }

    /**
     * stale entry cleanup — backlog §29 #2.
     *
     * 5분 마다 실행. lastEmit < now - 1h 인 entry 제거.
     * ConcurrentHashMap.entries.removeIf는 ConcurrentModificationException-safe.
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    fun cleanupStaleEntries() {
        val now = System.currentTimeMillis()
        val threshold = now - STALE_THRESHOLD_MS
        val beforeSize = lastEmitMs.size
        val removed = lastEmitMs.entries.removeIf { it.value < threshold }
        if (removed) {
            log.info(
                "[MarketOrderbookStompForwarder] cleanup — before={} after={} (stale {}h+ 제거)",
                beforeSize,
                lastEmitMs.size,
                STALE_THRESHOLD_MS / 1_000L / 3_600L,
            )
        }
    }
}
