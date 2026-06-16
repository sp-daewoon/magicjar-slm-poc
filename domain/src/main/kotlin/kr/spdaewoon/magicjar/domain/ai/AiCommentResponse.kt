package kr.spdaewoon.magicjar.domain.ai

import java.util.UUID

/**
 * AI 코멘트 응답 도메인 DTO (ADR 0026 §2-3 amend 정합).
 *
 * 흐름:
 * - adapter → AiCommentResponse 반환
 * - dispatcher: ai_comment_log INSERT (V49) → Kafka `ai.comment.responded.v1` publish
 * - api: STOMP `/topic/ai.explain.{requestId}` fanout → frontend `AiCard` 렌더링
 *
 * 비용 통제:
 * - `tokensIn` / `tokensOut` → `AiCostTracker` 가 `ai_comment_log.cost_usd` 누적 계산
 * - 월 누적 $10 초과 시 Slack CRITICAL alert (ADR 0095 amend S-AI-1)
 *
 * @property cached `true` 면 Redis prompt cache hit — LLM 호출 0건 / `tokensIn`·`tokensOut`·`latencyMs`=0
 * @property graceful `true` 면 graceful refuse (KillSwitch / 토큰 한도 / mock mode / disabled) — `content` 가 사용자 친화 한국어 메시지
 */
data class AiCommentResponse(
    val requestId: UUID,
    val provider: String,
    val model: String,
    val content: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val cached: Boolean = false,
    val graceful: Boolean = false,
)
