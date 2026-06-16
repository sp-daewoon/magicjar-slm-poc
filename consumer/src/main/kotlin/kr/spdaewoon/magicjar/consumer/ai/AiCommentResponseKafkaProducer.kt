package kr.spdaewoon.magicjar.consumer.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * AI 코멘트 응답 Kafka publisher — consumer adapter 호출 결과를 broadcast.
 *
 * 토픽: `ai.comment.responded.v1` (KafkaTopicConfig.aiCommentRespondedTopic)
 * partition key = userId.toString() — fanout 시 같은 사용자 ordering 보장.
 *
 * consumer 사이드 (api 모듈):
 * - `AiCommentResponseStompForwarder` — STOMP `/topic/ai.explain.{requestId}` 로 fanout
 * - `AiCommentResponseAuditListener` (api/messaging) — `ai_comment_log` INSERT (audit · cost 누적)
 */
@Component
class AiCommentResponseKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiCommentResponseKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Ai.COMMENT_RESPONDED
    }

    fun publish(event: AiResponseEnvelope) {
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate
            .send(TOPIC, event.userId.toString(), payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "[AiCommentResponse] publish 실패 — requestId={} provider={} err={}",
                        event.requestId,
                        event.provider,
                        ex.message,
                    )
                } else {
                    log.debug(
                        "[AiCommentResponse] publish OK — requestId={} provider={} cached={} graceful={} partition={}",
                        event.requestId,
                        event.provider,
                        event.cached,
                        event.graceful,
                        result?.recordMetadata?.partition(),
                    )
                }
            }
    }
}

/**
 * Kafka `ai.comment.responded.v1` 이벤트 payload v1 (ADR 0026 §2-4 amend).
 *
 * api 의 `AiExplainResponseEvent` 와 1:1 schema (consumer 모듈에서 동일 schema 정의 — domain 의존 회피).
 */
data class AiResponseEnvelope(
    val eventId: String = UUID.randomUUID().toString(),
    val requestId: UUID,
    val userId: Long,
    val commentType: String,
    val provider: String,
    val model: String,
    val promptTemplate: String,
    val content: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val costUsd: String,
    val cached: Boolean,
    val graceful: Boolean,
    val errorMessage: String? = null,
    val occurredAt: String = OffsetDateTime.now().toString(),
    val schemaVersion: Int = 1,
)
