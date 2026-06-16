package kr.spdaewoon.magicjar.api.daytrade

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.spdaewoon.magicjar.api.controller.daytrade.PaperPromoteRequest
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchCandidateJpaRepository
import kr.spdaewoon.magicjar.api.service.daytrade.DaytradePaperService
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * DaytradePaperService 단위 테스트 (ADR 0122 R2 · spec §3-R2 어댑터 수준).
 *
 * 검증:
 * 1. promote → origin=PAPER_DISCOVERY · status=ACTIVE 전략 INSERT (계약 LOCK).
 * 2. promote → 페이퍼 RiskConfig 파생 행 `<userId>:paper` 보장 (옵션 A · ADR 0121 사이징 정합).
 * 3. promote → STRATEGY owner WS 수요만 발행 (watchlist write 0 — 구조적 + verify).
 * 4. stop → 페이퍼 전략 soft delete(status=DELETED). 실 전략은 reject (오삭제 가드).
 * 5. promote → 페이퍼 비활성(enabled=false) 시 503.
 */
class DaytradePaperServiceTest {
    private val candidateRepository: StrategyResearchCandidateJpaRepository = mock()
    private val strategyRepositoryPort: StrategyRepositoryPort = mock()
    private val strategyJpaRepository: StrategyJpaRepository = mock()
    private val strategyPositionReadRepository: StrategyPositionReadJpaRepository = mock()
    private val strategyService: StrategyService = mock()
    private val riskConfigRepository: RiskConfigRepository = mock()
    private val instrumentJpaRepository: InstrumentJpaRepository = mock()
    private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val kafkaTemplate: KafkaTemplate<String, String> = mock()

    private fun service(enabled: Boolean = true) =
        DaytradePaperService(
            candidateRepository = candidateRepository,
            strategyRepositoryPort = strategyRepositoryPort,
            strategyJpaRepository = strategyJpaRepository,
            strategyPositionReadRepository = strategyPositionReadRepository,
            strategyService = strategyService,
            riskConfigRepository = riskConfigRepository,
            instrumentJpaRepository = instrumentJpaRepository,
            kisYamlDslParser = parser,
            objectMapper = objectMapper,
            kafkaTemplate = kafkaTemplate,
            paperEnabled = enabled,
            defaultCapitalKrw = BigDecimal("10000000"),
            maxInstances = 10,
        )

    private fun candidate() =
        StrategyResearchCandidateJpaEntity(
            id = 7L,
            candidateKey = "DAYTRADE_MOMENTUM_BREAKOUT",
            title = "모멘텀 돌파 단타",
            marketFit = "변동성 장",
            entryDsl = "rsi(14) > 50",
            targetProfitPct = BigDecimal("3.00"),
            stopLossPct = BigDecimal("2.00"),
            maxHoldBars = 12,
        )

    private fun complexity() =
        StrategyComplexity(
            astDepth = 1,
            nodeCount = 3,
            indicatorCallCount = 1,
            estimatedEvalTimeMs = 4,
            complexityClass = ComplexityClass.LOW,
        )

    @Test
    fun `promote는 origin=PAPER_DISCOVERY status=ACTIVE 전략을 INSERT 한다`() {
        whenever(candidateRepository.findByCandidateKey("DAYTRADE_MOMENTUM_BREAKOUT")).doReturn(candidate())
        whenever(strategyJpaRepository.countByUserIdAndOriginAndStatusNot(any(), any(), any())).doReturn(0L)
        whenever(strategyService.validateAndComputeComplexity(any())).doReturn(complexity())
        whenever(riskConfigRepository.findByUserId("1:paper")).doReturn(null)
        val captor = argumentCaptor<Strategy>()
        whenever(strategyRepositoryPort.save(captor.capture())).doAnswer { captor.lastValue.copy(id = 42L) }

        val result =
            service().promote(
                PaperPromoteRequest(
                    userId = 1L,
                    presetKey = "DAYTRADE_MOMENTUM_BREAKOUT",
                    codes = listOf("005930", "000660"),
                    interval = "1d",
                ),
            )

        val saved = captor.firstValue
        assertThat(saved.origin).isEqualTo(StrategyOrigin.PAPER_DISCOVERY)
        assertThat(saved.status).isEqualTo(StrategyStatus.ACTIVE)
        assertThat(saved.codes).containsExactly("005930", "000660")
        assertThat(saved.intervals).containsExactly("1d")
        // RiskPlan 복제 — setup target/stop
        assertThat(saved.definition.fixedStopLoss).isEqualByComparingTo("2.00")
        assertThat(saved.definition.takeProfitPct).isEqualByComparingTo("3.00")
        assertThat(result.strategyId).isEqualTo(42L)
        assertThat(result.origin).isEqualTo("PAPER_DISCOVERY")
    }

    @Test
    fun `promote는 페이퍼 RiskConfig 파생 행 userId paper 를 보장한다 (옵션 A ADR 0121 정합)`() {
        whenever(candidateRepository.findByCandidateKey(any())).doReturn(candidate())
        whenever(strategyJpaRepository.countByUserIdAndOriginAndStatusNot(any(), any(), any())).doReturn(0L)
        whenever(strategyService.validateAndComputeComplexity(any())).doReturn(complexity())
        whenever(riskConfigRepository.findByUserId("1:paper")).doReturn(null)
        whenever(strategyRepositoryPort.save(any())).doAnswer { (it.arguments[0] as Strategy).copy(id = 42L) }
        val configCaptor = argumentCaptor<RiskConfig>()
        whenever(riskConfigRepository.save(configCaptor.capture())).doAnswer { configCaptor.lastValue }

        service().promote(PaperPromoteRequest(userId = 1L, presetKey = "DAYTRADE_MOMENTUM_BREAKOUT", codes = listOf("005930")))

        val config = configCaptor.firstValue
        assertThat(config.userId).isEqualTo("1:paper")
        assertThat(config.maxPositionPercent).isEqualByComparingTo("10.00")
        assertThat(config.maxConcurrentPositions).isEqualTo(5)
        assertThat(config.dailyLossLimitPercent).isEqualByComparingTo("3.00")
        assertThat(config.maxTradesPerDay).isEqualTo(50)
    }

    @Test
    fun `promote는 STRATEGY owner WS 수요만 발행한다 (watchlist write 0)`() {
        whenever(candidateRepository.findByCandidateKey(any())).doReturn(candidate())
        whenever(strategyJpaRepository.countByUserIdAndOriginAndStatusNot(any(), any(), any())).doReturn(0L)
        whenever(strategyService.validateAndComputeComplexity(any())).doReturn(complexity())
        whenever(riskConfigRepository.findByUserId(any())).doReturn(null)
        whenever(strategyRepositoryPort.save(any())).doAnswer { (it.arguments[0] as Strategy).copy(id = 42L) }

        service().promote(PaperPromoteRequest(userId = 1L, presetKey = "DAYTRADE_MOMENTUM_BREAKOUT", codes = listOf("005930", "000660")))

        // 종목 수만큼 market.realtime.demand.v1 발행 (owner=STRATEGY — payload 내). 다른 토픽 0.
        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.Market.REALTIME_DEMAND), any(), any())
    }

    @Test
    fun `stop은 페이퍼 전략을 soft delete 한다`() {
        val paper =
            Strategy(
                id = 42L,
                userId = 1L,
                definition = mock(),
                complexity = complexity(),
                origin = StrategyOrigin.PAPER_DISCOVERY,
                status = StrategyStatus.ACTIVE,
                codes = listOf("005930"),
            )
        whenever(strategyRepositoryPort.findById(42L)).doReturn(paper)

        service().stop(42L, userId = 1L)

        verify(strategyJpaRepository).updateStatus(42L, StrategyStatus.DELETED.name)
    }

    @Test
    fun `stop은 실 전략(USER_DSL)을 reject 한다 (오삭제 가드)`() {
        val real =
            Strategy(
                id = 99L,
                userId = 1L,
                definition = mock(),
                complexity = complexity(),
                origin = StrategyOrigin.USER_DSL,
                status = StrategyStatus.ACTIVE,
            )
        whenever(strategyRepositoryPort.findById(99L)).doReturn(real)

        assertThatThrownBy { service().stop(99L, userId = 1L) }
            .isInstanceOf(ResponseStatusException::class.java)
        verify(strategyJpaRepository, never()).updateStatus(any(), any())
    }

    @Test
    fun `promote는 페이퍼 비활성 시 503`() {
        assertThatThrownBy {
            service(enabled = false).promote(PaperPromoteRequest(userId = 1L, presetKey = "X", codes = listOf("005930")))
        }.isInstanceOf(ResponseStatusException::class.java)
        verify(strategyRepositoryPort, never()).save(any())
    }
}
