package kr.spdaewoon.magicjar.api.persistence.backtest

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 백테스트 실행 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 정합 — Repository interface 내부 body 가진 fun 0건.
 * body 있는 helper는 [BacktestQueryService] private fun 위치 (caller class 분리).
 */
interface BacktestRunJpaRepository : JpaRepository<BacktestRunJpaEntity, Long> {
    fun findAllByUserId(
        userId: String,
        pageable: Pageable,
    ): Page<BacktestRunJpaEntity>

    fun findByIdAndUserId(
        id: Long,
        userId: String,
    ): BacktestRunJpaEntity?

    fun existsByIdAndUserId(
        id: Long,
        userId: String,
    ): Boolean

    @Query(
        """
        SELECT COUNT(b) > 0
        FROM BacktestRunJpaEntity b
        WHERE b.strategyId = :strategyId
          AND b.userId = :userId
          AND b.status = 'SUCCEEDED'
        """,
    )
    fun existsAutoActivationReadyRun(
        @Param("strategyId") strategyId: Long,
        @Param("userId") userId: String,
    ): Boolean
}
