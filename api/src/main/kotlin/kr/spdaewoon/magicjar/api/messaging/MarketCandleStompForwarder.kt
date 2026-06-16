package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Kafka market.candle.minute.closed.v1 → STOMP /topic/market.candle.minute.{code} 포워더
 * (ADR 0029 §2-3 — 1m closing 시 차트 series append).
 *
 * throttle 미적용 (1분마다 발행이라 이미 저빈도).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class MarketCandleStompForwarder(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketCandleStompForwarder::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Candle.MINUTE_CLOSED],
        groupId = "magicjar-api-stomp-candle",
    )
    fun onCandleMessage(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            messagingTemplate.convertAndSend(StompDestinations.marketCandleMinute(code), payload)
            log.debug("STOMP /topic/market.candle.minute.{} 발행: ts={}", code, payload["ts"])
        } catch (e: Exception) {
            log.error("market.candle.minute STOMP 포워드 실패: offset={} err={}", record.offset(), e.message)
        }
    }
}
