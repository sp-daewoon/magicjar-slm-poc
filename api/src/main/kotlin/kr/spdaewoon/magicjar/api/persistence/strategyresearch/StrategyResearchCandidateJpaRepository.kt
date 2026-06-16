package kr.spdaewoon.magicjar.api.persistence.strategyresearch

import org.springframework.data.jpa.repository.JpaRepository

interface StrategyResearchCandidateJpaRepository : JpaRepository<StrategyResearchCandidateJpaEntity, Long> {
    fun findAllByOrderBySortOrderAscIdAsc(): List<StrategyResearchCandidateJpaEntity>

    fun findAllByActiveTrueOrderBySortOrderAscIdAsc(): List<StrategyResearchCandidateJpaEntity>

    fun findByCandidateKey(candidateKey: String): StrategyResearchCandidateJpaEntity?

    fun existsByCandidateKey(candidateKey: String): Boolean
}
