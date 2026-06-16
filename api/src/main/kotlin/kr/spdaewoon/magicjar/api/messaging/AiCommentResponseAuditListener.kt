package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * Kafka `ai.comment.responded.v1` → `ai_comment_log` (V49) audit INSERT.
 *
 * 책임:
 * - 모든 LLM 호출 결과를 audit 표에 기록 (provider · tokens · cost · cached · graceful)
 * - `AiCostTracker.monthlySpend()` source-of-truth 갱신
 *
 * 분리 사유 (STOMP forwarder 와 별도):
 * - STOMP forwarder = realtime fanout (사용자 화면 즉시 응답)
 * - audit listener = persistence (월 비용 누적 + 토큰 한도 집계)
 * - 둘 다 같은 Kafka topic consume — 각자 독립 groupId (병행 fanout/persistence)
 *
 * 룰 5 정합: LLM provider import 0건 (본 클래스는 audit 만).
 */
@Component
class AiCommentResponseAuditListener(
    private val repository: AiCommentLogJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AiCommentResponseAuditListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Ai.COMMENT_RESPONDED],
        groupId = "magicjar-api-audit-ai-explain",
    )
    fun onAiResponse(record: ConsumerRecord<String, String>) {
        try {
            val payload = objectMapper.readValue(record.value(), ResponsePayload::class.java)
            val entity =
                AiCommentLogJpaEntity(
                    requestId = payload.requestId,
                    userId = payload.userId,
                    commentType = payload.commentType,
                    provider = payload.provider,
                    model = payload.model,
                    promptTemplate = payload.promptTemplate,
                    tokensIn = payload.tokensIn,
                    tokensOut = payload.tokensOut,
                    costUsd = runCatching { BigDecimal(payload.costUsd) }.getOrDefault(BigDecimal.ZERO),
                    latencyMs = payload.latencyMs,
                    cached = payload.cached,
                    graceful = payload.graceful,
                    errorMessage = payload.errorMessage,
                )
            repository.save(entity)
            log.debug(
                "[AiAudit] insert OK — requestId={} provider={} tokensIn={} tokensOut={} cost={} cached={} graceful={}",
                payload.requestId,
                payload.provider,
                payload.tokensIn,
                payload.tokensOut,
                payload.costUsd,
                payload.cached,
                payload.graceful,
            )
        } catch (e: Exception) {
            log.error("[AiAudit] insert 실패 — offset={} err={}", record.offset(), e.message)
        }
    }

    /**
     * `ai.comment.responded.v1` 이벤트 schema (consumer `AiResponseEnvelope` 와 1:1).
     *
     * api 측 단순 audit 용 별도 data class — 양쪽 모듈 데이터 클래스 의존 회피.
     */
    data class ResponsePayload(
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
    )
}
