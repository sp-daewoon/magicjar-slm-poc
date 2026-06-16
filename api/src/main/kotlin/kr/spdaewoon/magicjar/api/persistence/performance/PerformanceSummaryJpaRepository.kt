package kr.spdaewoon.magicjar.api.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * performance_summary Spring Data JPA Repository — 헌법 §6 (ADR 0051) abstract fun only LOCK.
 *
 * body 가진 fun 0건 — Spring Data Repository interface default fun 함정 방지.
 * helper 메서드는 [kr.spdaewoon.magicjar.api.persistence.performance.PerformanceRepositoryAdapter] 내부에.
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합 (V1 baseline).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface PerformanceSummaryJpaRepository : JpaRepository<PerformanceSummaryJpaEntity, Long> {
    fun findByUserIdAndPeriodAndPeriodStartAndPeriodEnd(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): PerformanceSummaryJpaEntity?
}
