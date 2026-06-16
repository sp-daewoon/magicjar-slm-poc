package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Kafka boundary closing 토픽 → STOMP 토픽 fanout (ADR 0064 C-1 채택).
 *
 * 구독 토픽 (ADR 0064 §4-5 + KafkaTopicConfig):
 *   market.candle.3m.closed.v1   → /topic/market.candle.3m.{code}
 *   market.candle.5m.closed.v1   → /topic/market.candle.5m.{code}
 *   market.candle.15m.closed.v1  → /topic/market.candle.15m.{code}
 *   market.candle.30m.closed.v1  → /topic/market.candle.30m.{code}
 *   market.candle.90m.closed.v1  → /topic/market.candle.90m.{code}
 *   market.candle.1h.closed.v1   → /topic/market.candle.1h.{code}
 *   market.candle.4h.closed.v1   → /topic/market.candle.4h.{code}
 *   market.candle.12h.closed.v1  → /topic/market.candle.12h.{code}
 *
 * payload schema (BoundaryAggregator publish 정합 — ADR 0064 §4-5):
 *   { code, interval, ts, open, high, low, close, volume, source, publishedAt }
 *
 * STOMP topic 패턴 (ADR 0029 §2-5 LOCK):
 *   /topic/market.candle.{interval}.{code}
 *   예: /topic/market.candle.5m.005930
 *
 * frontend subscription 정합 (ADR 0064 §7-1 frontend 디스패치):
 *   useSymbolCandles.ts IntervalToggle 선택값 기반 dynamic subscribe/unsubscribe.
 *
 * throttle 미적용 — boundary 마감은 이미 저빈도 (3m=130건/일, 1h=7건/일 per code).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class CandleClosedStompListener(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CandleClosedStompListener::class.java)

    /**
     * 3m boundary closing → STOMP /topic/market.candle.3m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-3m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M3_CLOSED],
        groupId = "magicjar-api-stomp-boundary-3m",
    )
    fun on3mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "3m")
    }

    /**
     * 5m boundary closing → STOMP /topic/market.candle.5m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-5m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M5_CLOSED],
        groupId = "magicjar-api-stomp-boundary-5m",
    )
    fun on5mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "5m")
    }

    /**
     * 15m boundary closing → STOMP /topic/market.candle.15m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-15m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M15_CLOSED],
        groupId = "magicjar-api-stomp-boundary-15m",
    )
    fun on15mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "15m")
    }

    /**
     * 30m boundary closing → STOMP /topic/market.candle.30m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-30m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M30_CLOSED],
        groupId = "magicjar-api-stomp-boundary-30m",
    )
    fun on30mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "30m")
    }

    /**
     * 90m boundary closing → STOMP /topic/market.candle.90m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-90m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M90_CLOSED],
        groupId = "magicjar-api-stomp-boundary-90m",
    )
    fun on90mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "90m")
    }

    /**
     * 1h boundary closing → STOMP /topic/market.candle.1h.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-1h
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.H1_CLOSED],
        groupId = "magicjar-api-stomp-boundary-1h",
    )
    fun on1hClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "1h")
    }

    /**
     * 4h boundary closing → STOMP /topic/market.candle.4h.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-4h
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.H4_CLOSED],
        groupId = "magicjar-api-stomp-boundary-4h",
    )
    fun on4hClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "4h")
    }

    /**
     * 12h final aggregate closing → STOMP /topic/market.candle.12h.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-12h
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.H12_CLOSED],
        groupId = "magicjar-api-stomp-boundary-12h",
    )
    fun on12hClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "12h")
    }

    /**
     * Kafka payload → STOMP /topic/market.candle.{interval}.{code} 전달.
     *
     * STOMP topic: /topic/market.candle.{interval}.{code}
     * 예: /topic/market.candle.5m.005930
     *
     * @param record Kafka ConsumerRecord
     * @param interval 캔들 인터벌 코드 (예: "5m")
     */
    private fun forwardToStomp(
        record: ConsumerRecord<String, String>,
        interval: String,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            val topic = StompDestinations.marketCandle(interval, code)
            messagingTemplate.convertAndSend(topic, payload)
            log.debug(
                "STOMP {} 발행: code={} ts={} close={}",
                topic,
                code,
                payload["ts"],
                payload["close"],
            )
        } catch (e: Exception) {
            log.error(
                "candle boundary STOMP 포워드 실패: interval={} offset={} err={}",
                interval,
                record.offset(),
                e.message,
            )
        }
    }
}
