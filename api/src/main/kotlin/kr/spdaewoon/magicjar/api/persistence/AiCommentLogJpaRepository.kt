package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant

/**
 * AI 코멘트 audit Spring Data JPA Repository (ADR 0026 §2-5 amend · V49).
 *
 * ADR 0051 default fun 함정 회피 LOCK:
 *   - interface 내부 body 가진 fun 발행 금지 (abstract / @Query 만 허용)
 *   - 비즈니스 헬퍼는 `AiCommentLogRepositoryAdapter` private fun 으로 이동
 *
 * 집계 쿼리:
 *   - `sumTokensByUserSince()` — 1일 토큰 한도 (`AiTokenLimitGuard`)
 *   - `sumCostByMonth()` — 월 비용 누적 (`AiCostTracker` · ADR 0095 amend S-AI-1)
 */
interface AiCommentLogJpaRepository : JpaRepository<AiCommentLogJpaEntity, Long> {
    /**
     * 1일 1사용자 토큰 한도 집계 (`tokens_in + tokens_out`).
     *
     * 호출자: `AiTokenLimitGuard.dailyTokens(userId)` — 한도 초과 시 graceful refuse.
     * 사용 인덱스: `idx_ai_comment_log_user_created` (V49).
     */
    @Query(
        """
        SELECT COALESCE(SUM(a.tokensIn + a.tokensOut), 0)
        FROM AiCommentLogJpaEntity a
        WHERE a.userId = :userId
          AND a.createdAt >= :since
          AND a.graceful = false
    """,
    )
    fun sumTokensByUserSince(
        @Param("userId") userId: Long,
        @Param("since") since: Instant,
    ): Long

    /**
     * 월 누적 비용 (`cost_usd` SUM).
     *
     * 호출자: `AiCostTracker.monthlySpend()` — $10 초과 시 ADR 0095 amend S-AI-1 Slack CRITICAL alert.
     * 사용 인덱스: `idx_ai_comment_log_provider_created` (V49).
     */
    @Query(
        """
        SELECT COALESCE(SUM(a.costUsd), 0)
        FROM AiCommentLogJpaEntity a
        WHERE a.createdAt >= :since
    """,
    )
    fun sumCostUsdSince(
        @Param("since") since: Instant,
    ): BigDecimal
}
