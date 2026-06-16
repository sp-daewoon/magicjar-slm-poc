package kr.spdaewoon.magicjar.api.ai.explain

import kr.spdaewoon.magicjar.api.persistence.AiCommentLogJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 1일 1사용자 토큰 한도 가드 (ADR 0026 §2-5 amend).
 *
 * default 한도: 1000 (env `magicjar.ai.daily-token-limit` override 가능).
 *
 * 집계:
 * - `AiCommentLogJpaRepository.sumTokensByUserSince(userId, now-24h)` — `tokens_in + tokens_out` SUM
 * - graceful=true 행은 제외 (refuse 행은 실제 LLM 호출 0건이므로 비용 0)
 *
 * 사용 인덱스: `idx_ai_comment_log_user_created` (V49) — DESC 정렬 + 1일 window 쿼리 최적.
 *
 * 호출 시점: Controller 의 KillSwitch 통과 직후 → 한도 초과 시 graceful refuse (Kafka publish 0건).
 */
@Component
class AiTokenLimitGuard(
    private val repository: AiCommentLogJpaRepository,
    @Value("\${magicjar.ai.daily-token-limit:1000}") private val dailyLimit: Long,
) {
    /**
     * 한도 초과 여부 검사.
     *
     * @return RefuseReason — null 이면 통과 / null 이 아니면 한도 초과 (한국어 사용자 메시지 + 잔여 토큰 0)
     */
    fun check(userId: Long): RefuseReason? {
        val since = Instant.now().minus(1, ChronoUnit.DAYS)
        val used = repository.sumTokensByUserSince(userId, since)
        return if (used >= dailyLimit) {
            RefuseReason(
                code = "DAILY_TOKEN_LIMIT_EXCEEDED",
                userMessage = "오늘 사용 가능한 AI 호출 한도($dailyLimit 토큰)를 초과했습니다. 내일 다시 시도해 주세요. (현재 사용량: $used 토큰)",
                used = used,
                limit = dailyLimit,
            )
        } else {
            null
        }
    }

    data class RefuseReason(
        val code: String,
        val userMessage: String,
        val used: Long,
        val limit: Long,
    )
}
