package kr.spdaewoon.magicjar.domain.ai

import java.util.UUID

/**
 * AI 코멘트 요청 도메인 DTO (ADR 0026 §2-3 amend 정합).
 *
 * 흐름:
 * - 사용자 클릭 → POST `/api/ai/explain` (api `AiExplainController`)
 * - api 가 본 DTO 구성 → Kafka `ai.comment.requested.v1` publish
 * - consumer `AiCommentDispatcherListener` consume → adapter 호출
 *
 * @property type 진입점 타입 (SYMBOL/CHART/STRATEGY/SIGNAL/MARKET)
 * @property context 진입점별 prompt 변수 치환 입력 (예: code · indicators · maPeriods · rsPeriod)
 * @property userId 호출자 사용자 ID (1일 토큰 한도 집계 키)
 * @property requestId STOMP fanout 라우팅 키 — `/topic/ai.explain.{requestId}` 매칭
 */
data class AiCommentRequest(
    val type: AiCommentType,
    val context: Map<String, Any>,
    val userId: Long,
    val requestId: UUID,
)
