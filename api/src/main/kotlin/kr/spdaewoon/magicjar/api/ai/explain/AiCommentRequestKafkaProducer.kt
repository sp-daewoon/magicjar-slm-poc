package kr.spdaewoon.magicjar.api.ai.explain

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * AI 코멘트 요청 Kafka publisher (ADR 0026 §2-4 amend).
 *
 * 토픽: `ai.comment.requested.v1` (KafkaTopicConfig.aiCommentRequestedTopic — 2026-05-24 amend 신규)
 * partition key = userId.toString() — 같은 사용자 요청 ordering 보장.
 *
 * 멱등성: 본 producer 는 dedup 0 — Controller 가 발급한 requestId (UUID) 가 fanout key.
 * consumer 는 같은 requestId 두 번 받으면 LLM 호출 1번만 + STOMP fanout 2번 (idempotent).
 */
@Component
class AiCommentRequestKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiCommentRequestKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Ai.COMMENT_REQUESTED
    }

    fun publish(event: AiExplainRequestEvent) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate
            .send(TOPIC, event.userId.toString(), payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "[AiCommentRequest] publish 실패 — requestId={} type={} err={}",
                        event.requestId,
                        event.type,
                        ex.message,
                    )
                } else {
                    log.debug(
                        "[AiCommentRequest] publish OK — requestId={} type={} partition={}",
                        event.requestId,
                        event.type,
                        result?.recordMetadata?.partition(),
                    )
                }
            }
    }
}
