package kr.spdaewoon.magicjar.domain.performance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 성과 통계 요약 도메인 엔티티 — ADR 0072 §9-3 Phase 2 산출물.
 *
 * Flyway V30 `performance_summary` 테이블 정합.
 *
 * - [period]: 기간 단위 (DAY·WEEK·MONTH·YEAR·ALL)
 * - [totalPnl]: 총 실현 손익 (원 단위, NUMERIC(18,2))
 * - [mddPct]: 최대 낙폭 % (NUMERIC(8,4))
 * - [winRatePct]: 승률 % (NUMERIC(5,2))
 * - [profitLossRatio]: 평균 손익비 avg(profit)/abs(avg(loss)) (null=손실 거래 없음 시)
 * - [metrics]: JSONB placeholder (Sharpe·Sortino·CAGR — backlog §61)
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
data class PerformanceSummary(
    val id: Long? = null,
    val userId: String,
    val period: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val totalPnl: BigDecimal,
    val mddPct: BigDecimal,
    val winRatePct: BigDecimal,
    val profitLossRatio: BigDecimal?,
    val tradeCount: Int,
    val metrics: String = "{}", // JSON placeholder
    val computedAt: Instant = Instant.now(),
)
