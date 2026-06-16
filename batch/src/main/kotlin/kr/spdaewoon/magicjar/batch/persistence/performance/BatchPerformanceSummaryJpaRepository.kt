package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * batch 모듈 performance_summary Repository — 헌법 §6 abstract fun only LOCK.
 *
 * DailyPerformanceJob UPSERT용.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface BatchPerformanceSummaryJpaRepository : JpaRepository<BatchPerformanceSummaryJpaEntity, Long> {
    fun findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): BatchPerformanceSummaryJpaEntity?
}
