package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * signal repository for consumer-side realtime signal persistence.
 */
interface ConsumerSignalJpaRepository : JpaRepository<ConsumerSignalJpaEntity, Long> {
    fun findByStrategyIdAndCodeAndIntervalAndTs(
        strategyId: Long,
        code: String,
        interval: String,
        ts: Instant,
    ): ConsumerSignalJpaEntity?

    @Modifying
    @Query("UPDATE ConsumerSignalJpaEntity s SET s.riskStatus = :status WHERE s.id = :id")
    fun updateRiskStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
    ): Int
}
