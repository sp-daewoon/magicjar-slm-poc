package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaRepository
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * 전략 리서치 후보 전략 CRUD 서비스 (research-candidates-db / V74).
 *
 * - 목록: 리서치 화면이 DB에서 후보를 읽도록 한다(코드 하드코딩 대체).
 * - 추가: 사용자의 저장된 전략(dsl_entry·손절/익절)을 후보로 승격(is_builtin=false).
 * - 삭제: builtin 후보도 삭제 허용(V74 seed는 ON CONFLICT DO NOTHING이라 부활 X — V70 preset 선례).
 */
@Service
class StrategyResearchCandidateService(
    private val candidateRepository: StrategyResearchCandidateJpaRepository,
    private val strategyRepository: StrategyJpaRepository,
    private val parser: KisYamlDslParser,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(StrategyResearchCandidateService::class.java)

    fun list(includeInactive: Boolean): List<StrategyResearchCandidateDefinitionDto> {
        val entities =
            if (includeInactive) {
                candidateRepository.findAllByOrderBySortOrderAscIdAsc()
            } else {
                candidateRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc()
            }
        return entities.map { it.toDto() }
    }

    @Transactional
    fun addFromStrategy(request: StrategyResearchCandidateFromStrategyRequestDto): StrategyResearchCandidateDefinitionDto {
        val userId =
            request.userId.toLongOrNull()
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId는 숫자여야 합니다: ${request.userId}")
        val strategy =
            strategyRepository
                .findById(request.strategyId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다: ${request.strategyId}") }
        if (strategy.userId != userId) {
            // 다른 사용자 전략 후보화 차단 (multi-tenant 격리).
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다: ${request.strategyId}")
        }

        val entryDsl = strategy.dslEntry.trim()
        if (entryDsl.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략의 진입 조건(dsl_entry)이 비어 있습니다.")
        }
        // 진입 DSL이 리서치 평가기에서 실행 가능한지 검증(파싱 + static 화이트리스트).
        runCatching {
            val ast = parser.parse(entryDsl)
            parser.validateStatic(ast)
        }.onFailure {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 진입 조건을 후보 DSL로 변환할 수 없습니다: ${it.message}")
        }

        val candidateKey = request.candidateKey ?: "USER_${strategy.id}"
        if (candidateRepository.existsByCandidateKey(candidateKey)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 같은 키의 후보가 있습니다: $candidateKey")
        }

        val sortOrder = request.sortOrder ?: nextSortOrder()
        val title = (request.title ?: strategy.nameKo ?: strategy.name).take(120)
        val entity =
            StrategyResearchCandidateJpaEntity(
                candidateKey = candidateKey,
                title = title,
                marketFit = "사용자 전략 '${strategy.name}'을 검증용 후보로 추가했습니다.".take(400),
                entryRules = objectMapper.writeValueAsString(strategyEntryRules(strategy)),
                entryDsl = entryDsl,
                targetProfitPct = strategy.takeProfitPct,
                stopLossPct = strategy.fixedStopLossPct,
                maxHoldBars = strategy.minHoldBars,
                sortOrder = sortOrder,
                active = true,
                builtIn = false,
            )
        val saved = candidateRepository.save(entity)
        log.info(
            "리서치 후보 추가(사용자 전략): userId={} strategyId={} candidateKey={} entryDsl='{}'",
            userId,
            strategy.id,
            candidateKey,
            entryDsl,
        )
        return saved.toDto()
    }

    @Transactional
    fun delete(id: Long) {
        if (!candidateRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "후보를 찾을 수 없습니다: $id")
        }
        candidateRepository.deleteById(id)
        log.info("리서치 후보 삭제: id={}", id)
    }

    private fun nextSortOrder(): Int {
        val max = candidateRepository.findAllByOrderBySortOrderAscIdAsc().maxOfOrNull { it.sortOrder } ?: 0
        return max + 10
    }

    private fun strategyEntryRules(strategy: StrategyJpaEntity): List<String> =
        buildList {
            add("진입: ${strategy.dslEntry.trim()}")
            strategy.dslExit?.takeIf { it.isNotBlank() }?.let { add("청산 DSL: ${it.trim()}") }
            strategy.takeProfitPct?.let { add("익절 ${it.toPlainString()}%") }
            strategy.fixedStopLossPct?.let { add("손절 ${it.toPlainString()}%") }
        }

    private fun StrategyResearchCandidateJpaEntity.toDto(): StrategyResearchCandidateDefinitionDto =
        StrategyResearchCandidateDefinitionDto(
            id = id ?: 0L,
            key = candidateKey,
            title = title,
            marketFit = marketFit,
            entryRules = parseRules(entryRules),
            entryDsl = entryDsl,
            targetProfitPct = targetProfitPct,
            stopLossPct = stopLossPct,
            maxHoldBars = maxHoldBars,
            sortOrder = sortOrder,
            active = active,
            builtIn = builtIn,
            updatedAt = updatedAt.toString(),
        )

    private fun parseRules(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, Array<String>::class.java).toList()
        }.getOrDefault(emptyList())
    }
}
