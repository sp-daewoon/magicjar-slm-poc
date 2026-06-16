package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaRepository
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateDefinition
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateProvider
import org.springframework.stereotype.Component

/**
 * DB 기반 리서치 후보 공급자 (research-candidates-db).
 *
 * V74 `strategy_research_candidate`의 활성(is_active=true) 후보를 sort_order 순으로 읽어
 * domain [ResearchCandidateDefinition]으로 매핑한다. 코드 하드코딩 후보 목록을 DB가 대체한다.
 */
@Component
class DbResearchCandidateProvider(
    private val repository: StrategyResearchCandidateJpaRepository,
    private val objectMapper: ObjectMapper,
) : ResearchCandidateProvider {
    override fun candidates(): List<ResearchCandidateDefinition> =
        repository
            .findAllByActiveTrueOrderBySortOrderAscIdAsc()
            .map { entity ->
                ResearchCandidateDefinition(
                    key = entity.candidateKey,
                    title = entity.title,
                    marketFit = entity.marketFit,
                    entryRules = parseRules(entity.entryRules),
                    entryDsl = entity.entryDsl,
                    isBuiltin = entity.builtIn,
                )
            }

    private fun parseRules(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { objectMapper.readValue<List<String>>(json) }.getOrDefault(emptyList())
    }
}
