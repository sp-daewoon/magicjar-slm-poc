package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Kafka `ai.comment.responded.v1` → STOMP `/topic/ai.explain.{requestId}` 포워더 (ADR 0026 §2-4 amend).
 *
 * 흐름:
 * 1. consumer `AiCommentDispatcherListener` 가 LLM 호출 결과를 `ai.comment.responded.v1` publish
 * 2. 본 forwarder 가 consume → STOMP `/topic/ai.explain.{requestId}` 으로 fanout
 * 3. frontend `AiCard` 가 subscribe → 응답 렌더링
 *
 * throttle 0 — AI 응답은 사용자 트리거 1회당 1건 fanout (멱등성 0 충돌).
 * 룰 5 위반 금지: LLM provider import 0건 (본 클래스는 fanout 만 — LLM 호출 0).
 */
@Component
class AiCommentResponseStompForwarder(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiCommentResponseStompForwarder::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Ai.COMMENT_RESPONDED],
        groupId = "magicjar-api-stomp-ai-explain",
    )
    fun onAiResponse(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val requestId = payload["requestId"]?.toString() ?: return
            messagingTemplate.convertAndSend(StompDestinations.aiExplain(requestId), payload)
            log.debug(
                "STOMP /topic/ai.explain.{} 발행 — provider={} cached={} graceful={}",
                requestId,
                payload["provider"],
                payload["cached"],
                payload["graceful"],
            )
        } catch (e: Exception) {
            log.error("ai.comment.responded STOMP 포워드 실패: offset={} err={}", record.offset(), e.message)
        }
    }
}
