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
 * Kafka market.tick.v1 → STOMP /topic/market.tick.{code} 포워더 (ADR 0029 §2-5).
 *
 * 1초 throttle (in-memory ConcurrentHashMap code → lastEmitMs) — 사용자 path 부담 차단.
 *
 * cleanup (backlog §29 #1, 안정성 Cycle 1):
 *   5분 마다 stale entry(lastEmit < now - 1h) 제거. KIS 구독 해지·종목 비활성으로
 *   더 이상 tick이 오지 않는 종목 entry가 영구 잔존하던 unbounded growth 패턴 차단.
 *   상한 상 매우 작아(50 종목 × 80 byte ≈ 4 KB) heap risk는 0이나 명시적 정책으로 정합.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class MarketTickStompForwarder(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketTickStompForwarder::class.java)

    // 1초 throttle: code → lastEmitMs
    internal val lastEmitMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val throttleMs = 1_000L

    companion object {
        /** stale entry 임계 (1시간). 마지막 tick이 1시간 이상 없으면 cleanup 대상. */
        const val STALE_THRESHOLD_MS = 60L * 60L * 1_000L

        /** cleanup 주기 (5분). @Scheduled fixedDelay = 직전 실행 완료 후 5분. */
        const val CLEANUP_INTERVAL_MS = 5L * 60L * 1_000L
    }

    @KafkaListener(
        topics = [KafkaTopics.Market.TICK],
        groupId = "magicjar-api-stomp-tick",
    )
    fun onTickMessage(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            // 1초 throttle
            val now = System.currentTimeMillis()
            val last = lastEmitMs.getOrDefault(code, 0L)
            if (now - last < throttleMs) {
                log.debug("tick throttle skip: code={}", code)
                return
            }
            lastEmitMs[code] = now

            messagingTemplate.convertAndSend(StompDestinations.marketTick(code), payload)
            log.debug("STOMP /topic/market.tick.{} 발행: price={}", code, payload["price"])
        } catch (e: Exception) {
            log.error("market.tick STOMP 포워드 실패: offset={} err={}", record.offset(), e.message)
        }
    }

    /**
     * stale entry cleanup — backlog §29 #1.
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
                "[MarketTickStompForwarder] cleanup — before={} after={} (stale {}h+ 제거)",
                beforeSize,
                lastEmitMs.size,
                STALE_THRESHOLD_MS / 1_000L / 3_600L,
            )
        }
    }
}
