package kr.spdaewoon.magicjar.api.persistence.scenario

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ScenarioRunJpaRepository : JpaRepository<ScenarioRunJpaEntity, Long> {
    fun findAllByUserId(
        userId: String,
        pageable: Pageable,
    ): Page<ScenarioRunJpaEntity>

    fun findByIdAndUserId(
        id: Long,
        userId: String,
    ): ScenarioRunJpaEntity?

    fun existsByIdAndUserId(
        id: Long,
        userId: String,
    ): Boolean
}

interface ScenarioEventJpaRepository : JpaRepository<ScenarioEventJpaEntity, Long> {
    fun findAllByRunId(
        runId: Long,
        pageable: Pageable,
    ): Page<ScenarioEventJpaEntity>
}

interface ScenarioOrderJpaRepository : JpaRepository<ScenarioOrderJpaEntity, Long>

interface ScenarioExecutionJpaRepository : JpaRepository<ScenarioExecutionJpaEntity, Long>

interface ScenarioPositionJpaRepository : JpaRepository<ScenarioPositionJpaEntity, Long> {
    fun findAllByRunIdOrderByCodeAsc(runId: Long): List<ScenarioPositionJpaEntity>
}
