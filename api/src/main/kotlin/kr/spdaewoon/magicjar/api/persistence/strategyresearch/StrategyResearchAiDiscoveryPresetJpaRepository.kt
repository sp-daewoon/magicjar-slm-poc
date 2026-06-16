package kr.spdaewoon.magicjar.api.persistence.strategyresearch

import org.springframework.data.jpa.repository.JpaRepository

interface StrategyResearchAiDiscoveryPresetJpaRepository : JpaRepository<StrategyResearchAiDiscoveryPresetJpaEntity, String> {
    fun findAllByOrderBySortOrderAscIdAsc(): List<StrategyResearchAiDiscoveryPresetJpaEntity>
}
