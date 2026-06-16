package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaRepository
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.Optional

class StrategyResearchCandidateServiceTest {
    private val candidateRepository: StrategyResearchCandidateJpaRepository = mock()
    private val strategyRepository: StrategyJpaRepository = mock()
    private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val service =
        StrategyResearchCandidateService(
            candidateRepository = candidateRepository,
            strategyRepository = strategyRepository,
            parser = parser,
            objectMapper = objectMapper,
        )

    @Test
    fun `addFromStrategy promotes a user strategy into a non-builtin candidate`() {
        whenever(strategyRepository.findById(31L)).thenReturn(Optional.of(strategy()))
        whenever(candidateRepository.existsByCandidateKey("USER_31")).thenReturn(false)
        whenever(candidateRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(emptyList())
        whenever(candidateRepository.save(any())).thenAnswer { it.arguments[0] }

        val dto =
            service.addFromStrategy(
                StrategyResearchCandidateFromStrategyRequestDto(
                    userId = "74683223",
                    strategyId = 31L,
                ),
            )

        dto.key shouldBe "USER_31"
        dto.builtIn shouldBe false
        dto.entryDsl shouldBe "close crosses_above ma(10) and close > open"
        dto.targetProfitPct shouldBe BigDecimal("2.0000")
        dto.stopLossPct shouldBe BigDecimal("1.0000")
    }

    @Test
    fun `addFromStrategy rejects a strategy owned by another user`() {
        whenever(strategyRepository.findById(31L)).thenReturn(Optional.of(strategy(userId = 999L)))

        val ex =
            shouldThrow<ResponseStatusException> {
                service.addFromStrategy(
                    StrategyResearchCandidateFromStrategyRequestDto(userId = "74683223", strategyId = 31L),
                )
            }
        ex.statusCode.value() shouldBe 404
    }

    @Test
    fun `addFromStrategy rejects an unparseable entry dsl`() {
        whenever(strategyRepository.findById(31L)).thenReturn(
            Optional.of(strategy(dslEntry = "this is >>> not @@ valid dsl")),
        )

        val ex =
            shouldThrow<ResponseStatusException> {
                service.addFromStrategy(
                    StrategyResearchCandidateFromStrategyRequestDto(userId = "74683223", strategyId = 31L),
                )
            }
        ex.statusCode.value() shouldBe 400
    }

    @Test
    fun `addFromStrategy rejects a duplicate candidate key`() {
        whenever(strategyRepository.findById(31L)).thenReturn(Optional.of(strategy()))
        whenever(candidateRepository.existsByCandidateKey("USER_31")).thenReturn(true)

        val ex =
            shouldThrow<ResponseStatusException> {
                service.addFromStrategy(
                    StrategyResearchCandidateFromStrategyRequestDto(userId = "74683223", strategyId = 31L),
                )
            }
        ex.statusCode.value() shouldBe 409
    }

    @Test
    fun `delete throws 404 when candidate is missing`() {
        whenever(candidateRepository.existsById(7L)).thenReturn(false)

        val ex = shouldThrow<ResponseStatusException> { service.delete(7L) }
        ex.statusCode.value() shouldBe 404
    }

    @Test
    fun `list maps entities to dtos`() {
        whenever(candidateRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(
            listOf(
                StrategyResearchCandidateJpaEntity(
                    id = 1L,
                    candidateKey = "MA10_RECLAIM_SCALP",
                    title = "MA10 회복 반등 스캘프",
                    marketFit = "fit",
                    entryRules = "[\"규칙1\",\"규칙2\"]",
                    entryDsl = "close crosses_above ma(10)",
                    sortOrder = 10,
                ),
            ),
        )

        val result = service.list(includeInactive = false)

        result.size shouldBe 1
        result.first().entryRules shouldBe listOf("규칙1", "규칙2")
        result.first().builtIn shouldBe true
    }

    private fun strategy(
        userId: Long = 74683223L,
        dslEntry: String = "close crosses_above ma(10) and close > open",
    ): StrategyJpaEntity =
        StrategyJpaEntity(
            id = 31L,
            userId = userId,
            name = "내 눌림목 전략",
            nameKo = "내 눌림목 전략",
            dslEntry = dslEntry,
            dslExit = "stop_loss(1%)",
            fixedStopLossPct = BigDecimal("1.0000"),
            takeProfitPct = BigDecimal("2.0000"),
            minHoldBars = 5,
        )
}
