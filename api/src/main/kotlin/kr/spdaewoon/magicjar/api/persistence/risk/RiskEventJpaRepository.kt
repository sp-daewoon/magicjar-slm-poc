package kr.spdaewoon.magicjar.api.persistence.risk

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * risk_event Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [RiskEventJpaAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskEventJpaRepository : JpaRepository<RiskEventJpaEntity, Long> {
    fun findByUserIdAndTsBetween(
        userId: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<RiskEventJpaEntity>

    @Query(
        "SELECT COUNT(e) FROM RiskEventJpaEntity e " +
            "WHERE e.signalId IN (" +
            "  SELECT s.id FROM SignalJpaEntity s WHERE s.strategyId = :strategyId" +
            ") AND e.verdict = 'BLOCKED' AND e.ts >= :since",
    )
    fun countRecentBlockedByStrategy(
        @Param("strategyId") strategyId: Long,
        @Param("since") since: Instant,
    ): Int

    /**
     * 무해 가드 rule 제외 BLOCKED 카운트 (F9 — consumer-side AutoPauseGuard 와 동일 의미).
     * caller(adapter)는 비어있지 않은 set 일 때만 본 query 를 사용한다(빈 collection IN 절 hazard 회피).
     */
    @Query(
        "SELECT COUNT(e) FROM RiskEventJpaEntity e " +
            "WHERE e.signalId IN (" +
            "  SELECT s.id FROM SignalJpaEntity s WHERE s.strategyId = :strategyId" +
            ") AND e.verdict = 'BLOCKED' AND e.ts >= :since AND e.rule NOT IN :excludedRules",
    )
    fun countRecentBlockedByStrategyExcludingRules(
        @Param("strategyId") strategyId: Long,
        @Param("since") since: Instant,
        @Param("excludedRules") excludedRules: Set<String>,
    ): Int
}
