package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 전략 평가 metric Spring Data JPA 리포지터리.
 *
 * ADR 0051 — Repository interface 내부 body 가진 fun 0건.
 */
interface StrategyEvalMetricRepository : JpaRepository<StrategyEvalMetricJpaEntity, Long> {
    fun findAllByStrategyIdOrderByEvaluatedAtDesc(
        strategyId: Long,
        pageable: Pageable,
    ): List<StrategyEvalMetricJpaEntity>

    fun countByStrategyId(strategyId: Long): Long

    @Modifying
    @Query(
        nativeQuery = true,
        value =
            "DELETE FROM strategy_eval_metric WHERE strategy_id = :strategyId " +
                "AND id NOT IN (SELECT id FROM strategy_eval_metric WHERE strategy_id = :strategyId " +
                "ORDER BY evaluated_at DESC LIMIT :keepCount)",
    )
    fun retainLatestN(
        @Param("strategyId") strategyId: Long,
        @Param("keepCount") keepCount: Int,
    ): Int
}
