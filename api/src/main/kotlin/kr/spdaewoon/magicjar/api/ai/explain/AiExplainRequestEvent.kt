package kr.spdaewoon.magicjar.api.ai.explain

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Kafka `ai.comment.requested.v1` 이벤트 schema v1 (ADR 0026 §2-4 amend).
 *
 * publisher : api `AiCommentRequestKafkaProducer`
 * consumer  : consumer `AiCommentDispatcherListener`
 *
 * 부하 예측 (ADR 0026 §2-2 amend): 1,000 msg/day = 0.012 msg/s — 매우 가벼움.
 *
 * partition key = userId (1일 한도 정확성 + ordering 보장).
 */
data class AiExplainRequestEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val requestId: UUID,
    val userId: Long,
    val type: String,
    val contextJson: String,
    val promptTemplateKey: String,
    val prompt: String,
    val occurredAt: String = OffsetDateTime.now().toString(),
    val schemaVersion: Int = 1,
)

/**
 * Kafka `ai.comment.responded.v1` 이벤트 schema v1.
 *
 * publisher : consumer `AiCommentDispatcherListener`
 * consumer  : api `AiCommentResponseStompForwarder` (STOMP fanout)
 */
data class AiExplainResponseEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val requestId: UUID,
    val userId: Long,
    val provider: String,
    val model: String,
    val content: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val cached: Boolean,
    val graceful: Boolean,
    val occurredAt: String = OffsetDateTime.now().toString(),
    val schemaVersion: Int = 1,
)
