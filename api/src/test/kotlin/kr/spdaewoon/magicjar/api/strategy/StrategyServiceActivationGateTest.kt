package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

class StrategyServiceActivationGateTest {
    private val strategyRepositoryPort = mockk<StrategyRepositoryPort>(relaxed = true)
    private val strategyJpaRepository = mockk<StrategyJpaRepository>(relaxed = true)
    private val backtestRunJpaRepository = mockk<BacktestRunJpaRepository>(relaxed = true)
    private val redisStrategyCache = mockk<RedisStrategyCache>(relaxed = true)
    private val service =
        StrategyService(
            strategyRepositoryPort = strategyRepositoryPort,
            strategyJpaRepository = strategyJpaRepository,
            backtestRunJpaRepository = backtestRunJpaRepository,
            redisStrategyCache = redisStrategyCache,
            astDepthLimit = 32,
            nodeCountLimit = 10_000,
            indicatorCallLimit = 50,
        )

    @Test
    fun `PAUSED to ACTIVE는 성공한 백테스트 없으면 차단한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.PAUSED)
        every { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") } returns false

        assertThatThrownBy { service.toggleStatus(1L, StrategyStatus.ACTIVE) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("성공한 백테스트")

        verify(exactly = 0) { strategyJpaRepository.updateStatus(1L, StrategyStatus.ACTIVE.name) }
        verify(exactly = 0) { strategyRepositoryPort.enable(1L) }
    }

    @Test
    fun `대상 종목 없는 전략은 ACTIVE 전환을 차단한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.PAUSED, codes = emptyList())

        assertThatThrownBy { service.toggleStatus(1L, StrategyStatus.ACTIVE) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("자동매매 대상 종목")

        verify(exactly = 0) { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") }
        verify(exactly = 0) { strategyRepositoryPort.enable(1L) }
    }

    @Test
    fun `손절 익절 RiskPlan 없는 전략은 ACTIVE 전환을 차단한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { strategyRepositoryPort.findById(1L) } returns
            strategy(status = StrategyStatus.PAUSED, stopLossPct = null, takeProfitPct = null)

        assertThatThrownBy { service.toggleStatus(1L, StrategyStatus.ACTIVE) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("손절/익절 RiskPlan")

        verify(exactly = 0) { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") }
        verify(exactly = 0) { strategyRepositoryPort.enable(1L) }
    }

    @Test
    fun `단타형 RiskPlan은 익절 기준이 손절 기준 이하라도 전략 저장을 허용한다`() {
        every { strategyRepositoryPort.save(any()) } answers { firstArg() }

        val saved =
            service.create(
                userId = 1234L,
                name = "scalping_risk_plan",
                nameKo = "단타 손익비",
                entryAst = sampleEntryAst(),
                exitAst = null,
                universe = Universe("KOSPI"),
                tf = "1m",
                entryText = "close > 0",
                exitText = null,
                codes = listOf("005930"),
                intervals = listOf("1m"),
                stopLossPct = BigDecimal("5.00"),
                takeProfitPct = BigDecimal("3.00"),
                requireCodes = true,
            )

        assertThat(saved.definition.fixedStopLoss).isEqualByComparingTo("5.00")
        assertThat(saved.definition.takeProfitPct).isEqualByComparingTo("3.00")
        verify(exactly = 1) { strategyRepositoryPort.save(any()) }
    }

    @Test
    fun `성공한 백테스트가 있으면 ACTIVE 전환 시 비활성 사유를 함께 지운다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") } returns true
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.ACTIVE)

        service.toggleStatus(1L, StrategyStatus.ACTIVE)

        verify(exactly = 1) { strategyRepositoryPort.enable(1L) }
        verify(exactly = 0) { strategyJpaRepository.updateStatus(1L, StrategyStatus.ACTIVE.name) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    @Test
    fun `BUY_BLOCKED to ACTIVE는 사용자 명시 재개로 비활성 사유를 지운다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "BUY_BLOCKED")
        every { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") } returns true
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.ACTIVE)

        service.toggleStatus(1L, StrategyStatus.ACTIVE)

        verify(exactly = 1) { strategyRepositoryPort.enable(1L) }
        verify(exactly = 0) { strategyJpaRepository.updateStatus(1L, StrategyStatus.ACTIVE.name) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    @Test
    fun `페이퍼 전략은 성공한 백테스트 없이 ACTIVE 전환을 허용한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { strategyRepositoryPort.findById(1L) } returns
            strategy(status = StrategyStatus.PAUSED, origin = StrategyOrigin.PAPER_DISCOVERY)

        service.toggleStatus(1L, StrategyStatus.ACTIVE)

        verify(exactly = 0) { backtestRunJpaRepository.existsAutoActivationReadyRun(any(), any()) }
        verify(exactly = 1) { strategyRepositoryPort.enable(1L) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    @Test
    fun `BUY_BLOCKED to PAUSED는 전체 자동매매 중지로 허용한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "BUY_BLOCKED")
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.PAUSED)

        service.toggleStatus(1L, StrategyStatus.PAUSED)

        verify(exactly = 1) { strategyJpaRepository.updateStatus(1L, StrategyStatus.PAUSED.name) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    private fun entity(status: String) =
        StrategyJpaEntity(
            id = 1L,
            userId = 1234L,
            name = "test",
            nameKo = "테스트",
            dslEntry = "close > ma(20)",
            dslExit = null,
            astDepth = 1,
            nodeCount = 1,
            indicatorCallCount = 1,
            estimatedEvalTimeMs = 1,
            complexityClass = ComplexityClass.LOW.name,
            status = status,
        )

    private fun strategy(
        status: StrategyStatus,
        codes: List<String> = listOf("005930"),
        stopLossPct: BigDecimal? = BigDecimal("5.00"),
        takeProfitPct: BigDecimal? = BigDecimal("10.00"),
        origin: StrategyOrigin = StrategyOrigin.USER_DSL,
    ) = Strategy(
        id = 1L,
        userId = 1234L,
        definition =
            StrategyDefinition(
                dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                universe = Universe("KOSPI"),
                tf = "D1",
                fixedStopLoss = stopLossPct,
                takeProfitPct = takeProfitPct,
            ),
        complexity =
            StrategyComplexity(
                astDepth = 1,
                nodeCount = 1,
                indicatorCallCount = 1,
                estimatedEvalTimeMs = 1,
                complexityClass = ComplexityClass.LOW,
            ),
        status = status,
        origin = origin,
        codes = codes,
        intervals = listOf("1d"),
    )

    private fun sampleEntryAst() =
        Condition(
            left = IndicatorRef("close"),
            operator = Operator.GT,
            right = LiteralValue(BigDecimal.ZERO),
        )
}
