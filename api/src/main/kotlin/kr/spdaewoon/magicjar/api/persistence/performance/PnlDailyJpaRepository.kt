package kr.spdaewoon.magicjar.api.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * pnl_daily Spring Data JPA Repository — 헌법 §6 (ADR 0051) abstract fun only LOCK.
 *
 * body 가진 fun 0건 — Spring Data Repository interface default fun 함정 방지.
 * helper 메서드는 [kr.spdaewoon.magicjar.api.persistence.performance.PerformanceRepositoryAdapter] 내부에.
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합 (V1 baseline).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface PnlDailyJpaRepository : JpaRepository<PnlDailyJpaEntity, Long> {
    fun findByUserIdAndTradeDate(
        userId: String,
        tradeDate: LocalDate,
    ): PnlDailyJpaEntity?

    fun findByUserIdAndTradeDateBetweenOrderByTradeDateAsc(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<PnlDailyJpaEntity>

    @Query(
        """
        SELECT p.trade_date FROM pnl_daily p
        WHERE p.user_id = :userId
        ORDER BY p.trade_date ASC
        LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findEarliestTradeDateByUserId(
        @Param("userId") userId: String,
    ): LocalDate?
}
