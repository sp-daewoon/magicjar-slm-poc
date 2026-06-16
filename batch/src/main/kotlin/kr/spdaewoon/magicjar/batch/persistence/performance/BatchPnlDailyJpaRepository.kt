package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * batch 모듈 pnl_daily Repository — 헌법 §6 abstract fun only LOCK.
 *
 * DailyPerformanceJob UPSERT + aggregate 조회용.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface BatchPnlDailyJpaRepository : JpaRepository<BatchPnlDailyJpaEntity, Long> {
    fun findByUserIdAndTradeDate(
        userId: String,
        tradeDate: LocalDate,
    ): BatchPnlDailyJpaEntity?

    fun findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BatchPnlDailyJpaEntity>
}
