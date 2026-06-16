package kr.spdaewoon.magicjar.api.persistence.signal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * 시그널 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [SignalJpaAdapter] private fun 위치.
 *
 * V19 추가: risk_status 컬럼 UPDATE — SignalRiskStatusJpaAdapter 호출 (ADR 0067 §5-5 F-1).
 *
 * // AI 호출 0 — T-SIG 트랙 정책
 */
interface SignalJpaRepository :
    JpaRepository<SignalJpaEntity, Long>,
    JpaSpecificationExecutor<SignalJpaEntity> {
    fun findByStrategyIdAndCodeAndIntervalAndTs(
        strategyId: Long,
        code: String,
        interval: String,
        ts: Instant,
    ): SignalJpaEntity?

    @Modifying
    @Query("UPDATE SignalJpaEntity s SET s.riskStatus = :status WHERE s.id = :id")
    fun updateRiskStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
    ): Int
}
