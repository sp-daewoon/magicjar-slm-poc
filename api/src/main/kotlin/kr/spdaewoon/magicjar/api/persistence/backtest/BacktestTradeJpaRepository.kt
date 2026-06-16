package kr.spdaewoon.magicjar.api.persistence.backtest

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 백테스트 매매 기록 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 정합 — Repository interface 내부 body 가진 fun 0건.
 * body 있는 helper는 [BacktestQueryService] private fun 위치 (caller class 분리).
 */
interface BacktestTradeJpaRepository : JpaRepository<BacktestTradeJpaEntity, Long> {
    fun findAllByRunId(
        runId: Long,
        pageable: Pageable,
    ): Page<BacktestTradeJpaEntity>

    fun countByRunId(runId: Long): Long
}
