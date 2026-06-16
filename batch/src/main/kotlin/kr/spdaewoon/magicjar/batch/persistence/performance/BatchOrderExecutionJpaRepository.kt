package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * batch 모듈 order_execution 읽기 전용 Repository — 헌법 §6 abstract fun only LOCK.
 *
 * DailyPerformanceJob 집계용 — 체결 이력 replay.
 * order_request JOIN을 통해 user_id 조회.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface BatchOrderExecutionJpaRepository : JpaRepository<BatchOrderExecutionJpaEntity, Long> {
    @Query(
        """
        SELECT e FROM BatchOrderExecutionJpaEntity e
        JOIN e.orderRequest r
        WHERE r.userId = :userId
          AND e.executedAt >= :from
          AND e.executedAt < :to
        ORDER BY e.executedAt ASC
        """,
    )
    fun findByUserIdAndExecutedAtRange(
        @Param("userId") userId: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<BatchOrderExecutionJpaEntity>

    @Query(
        """
        SELECT e FROM BatchOrderExecutionJpaEntity e
        JOIN FETCH e.orderRequest r
        WHERE r.userId = :userId
          AND e.executedAt < :to
        ORDER BY e.executedAt ASC, e.id ASC
        """,
    )
    fun findByUserIdAndExecutedAtBefore(
        @Param("userId") userId: String,
        @Param("to") to: Instant,
    ): List<BatchOrderExecutionJpaEntity>
}
