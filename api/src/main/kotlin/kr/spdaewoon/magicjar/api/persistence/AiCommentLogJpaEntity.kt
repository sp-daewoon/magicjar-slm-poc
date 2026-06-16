package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * AI 코멘트 호출 audit JPA 엔티티 (ADR 0026 §2-5 amend · Flyway V49 — 2026-05-24 R0 amend shift).
 *
 * 테이블: ai_comment_log (V49__ai_comment_log.sql)
 *
 * 책임 (V49 본문 주석 정합):
 * - 모든 LLM 호출 audit (provider · model · type · tokens · latency · cost)
 * - 1일 1사용자 토큰 한도 집계 (`AiTokenLimitGuard` 본 엔티티 조회)
 * - 월 비용 누적 (`AiCostTracker.monthlySpend()` cost_usd SUM)
 * - 캐시 hit 감사 (`cached=TRUE` 행은 LLM 호출 0건 / tokens=0)
 * - graceful refuse 감사 (`graceful=TRUE` 행은 KillSwitch / 토큰 한도 / mock)
 *
 * 본 엔티티는 unidirectional — `ai_comment_log` 단독 표 (FK 0건).
 *
 * // AI 호출 0 건 (본 엔티티는 audit 만 — provider 호출은 consumer adapter)
 */
@Entity
@Table(name = "ai_comment_log")
class AiCommentLogJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "request_id", nullable = false)
    val requestId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "comment_type", length = 32, nullable = false)
    val commentType: String,
    @Column(name = "provider", length = 32, nullable = false)
    val provider: String,
    @Column(name = "model", length = 128, nullable = false)
    val model: String,
    @Column(name = "prompt_template", length = 64, nullable = false)
    val promptTemplate: String,
    @Column(name = "tokens_in", nullable = false)
    val tokensIn: Int = 0,
    @Column(name = "tokens_out", nullable = false)
    val tokensOut: Int = 0,
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    val costUsd: BigDecimal = BigDecimal.ZERO,
    @Column(name = "latency_ms", nullable = false)
    val latencyMs: Long = 0L,
    @Column(name = "cached", nullable = false)
    val cached: Boolean = false,
    @Column(name = "graceful", nullable = false)
    val graceful: Boolean = false,
    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        requestId = UUID.randomUUID(),
        userId = 0L,
        commentType = "",
        provider = "",
        model = "",
        promptTemplate = "",
    )
}
