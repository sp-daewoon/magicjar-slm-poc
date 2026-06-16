package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * risk_event repository for consumer-side risk persistence.
 */
interface ConsumerRiskEventJpaRepository : JpaRepository<ConsumerRiskEventJpaEntity, Long> {
    @Query(
        """
        SELECT COUNT(e)
        FROM ConsumerRiskEventJpaEntity e
        WHERE e.verdict = 'BLOCKED'
          AND e.ts >= :since
          AND e.signalId IN (
              SELECT s.id
              FROM ConsumerSignalJpaEntity s
              WHERE s.strategyId = :strategyId
          )
        """,
    )
    fun countRecentBlockedByStrategy(
        @Param("strategyId") strategyId: Long,
        @Param("since") since: Instant,
    ): Int

    /**
     * 무해 가드 rule 제외 BLOCKED 카운트 (F9 — AutoPauseGuard 자동정지 임계용).
     *
     * [excludedRules] 에 담긴 rule 명은 카운트에서 빠진다 — 보유 0 SELL 차단·중복 매수 차단 등 정상 거동이
     * 전략 자동정지에 기여하지 않게 한다. caller(adapter)는 비어있지 않은 set 일 때만 본 query 를 사용한다
     * (빈 collection IN 절 hazard 회피).
     */
    @Query(
        """
        SELECT COUNT(e)
        FROM ConsumerRiskEventJpaEntity e
        WHERE e.verdict = 'BLOCKED'
          AND e.ts >= :since
          AND e.rule NOT IN :excludedRules
          AND e.signalId IN (
              SELECT s.id
              FROM ConsumerSignalJpaEntity s
              WHERE s.strategyId = :strategyId
          )
        """,
    )
    fun countRecentBlockedByStrategyExcludingRules(
        @Param("strategyId") strategyId: Long,
        @Param("since") since: Instant,
        @Param("excludedRules") excludedRules: Set<String>,
    ): Int
}
