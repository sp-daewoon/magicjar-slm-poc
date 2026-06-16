package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
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

/**
 * 잔익절 방어 — A안(저장 경고) + B안(minHoldBars 검증/저장) StrategyService 단위 검증.
 *
 * - A안: 익절% ≤ 0.3 → exitNoiseWarnings 1건(비차단). 미설정·임계 초과 무경고.
 * - B안: minHoldBars 0/null 비활성 통일·음수/cap 초과 400·정상 저장.
 */
class StrategyExitNoiseGuardTest {
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

    // === A안 — exitNoiseWarnings ===

    @Test
    fun `익절 0_2퍼센트 임계 미만이면 잔익절 경고 1건`() {
        val warnings = service.exitNoiseWarnings(strategy(takeProfitPct = BigDecimal("0.2")))
        assertThat(warnings).hasSize(1)
        assertThat(warnings[0]).contains("잔익절")
    }

    @Test
    fun `익절 0_3퍼센트 경계 정확이면 경고 — 경계 포함`() {
        val warnings = service.exitNoiseWarnings(strategy(takeProfitPct = BigDecimal("0.3")))
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `익절 0_31퍼센트(임계 초과)면 무경고`() {
        val warnings = service.exitNoiseWarnings(strategy(takeProfitPct = BigDecimal("0.31")))
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `익절 미설정(null)이면 무경고`() {
        val warnings = service.exitNoiseWarnings(strategy(takeProfitPct = null))
        assertThat(warnings).isEmpty()
    }

    // === B안 — minHoldBars 검증·정규화 ===

    @Test
    fun `minHoldBars 정상값은 저장된다`() {
        val saved =
            service.create(
                userId = 1234L,
                name = "min_hold",
                nameKo = "최소보유",
                entryAst = sampleEntryAst(),
                exitAst = null,
                universe = Universe("KOSPI"),
                tf = "5m",
                entryText = "close > 0",
                exitText = null,
                codes = listOf("005930"),
                intervals = listOf("5m"),
                stopLossPct = BigDecimal("5.00"),
                takeProfitPct = BigDecimal("3.00"),
                minHoldBars = 3,
                requireCodes = true,
            )
        assertThat(saved.definition.minHoldBars).isEqualTo(3)
    }

    @Test
    fun `minHoldBars 0은 비활성(null)로 통일된다`() {
        val saved = createWithMinHold(0)
        assertThat(saved.definition.minHoldBars).isNull()
    }

    @Test
    fun `minHoldBars null이면 현행 비활성`() {
        val saved = createWithMinHold(null)
        assertThat(saved.definition.minHoldBars).isNull()
    }

    @Test
    fun `minHoldBars 음수는 400`() {
        assertThatThrownBy { createWithMinHold(-1) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("minHoldBars")
    }

    @Test
    fun `minHoldBars cap(500) 초과는 400`() {
        assertThatThrownBy { createWithMinHold(501) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("minHoldBars")
    }

    private fun createWithMinHold(minHoldBars: Int?): Strategy =
        service.create(
            userId = 1234L,
            name = "min_hold",
            nameKo = null,
            entryAst = sampleEntryAst(),
            exitAst = null,
            universe = Universe("KOSPI"),
            tf = "5m",
            entryText = "close > 0",
            exitText = null,
            codes = listOf("005930"),
            intervals = listOf("5m"),
            stopLossPct = BigDecimal("5.00"),
            takeProfitPct = BigDecimal("3.00"),
            minHoldBars = minHoldBars,
            requireCodes = true,
        )

    private fun strategy(takeProfitPct: BigDecimal?) =
        Strategy(
            id = 1L,
            userId = 1234L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                    universe = Universe("KOSPI"),
                    tf = "5m",
                    fixedStopLoss = BigDecimal("5.00"),
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
            status = StrategyStatus.PAUSED,
            codes = listOf("005930"),
            intervals = listOf("5m"),
        )

    private fun sampleEntryAst() =
        Condition(
            left = IndicatorRef("close"),
            operator = Operator.GT,
            right = LiteralValue(BigDecimal.ZERO),
        )

    init {
        every { strategyRepositoryPort.save(any()) } answers { firstArg() }
    }
}
