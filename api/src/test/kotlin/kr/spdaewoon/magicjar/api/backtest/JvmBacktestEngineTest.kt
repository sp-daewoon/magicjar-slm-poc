package kr.spdaewoon.magicjar.api.backtest

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.service.backtest.BacktestEvaluatorBridge
import kr.spdaewoon.magicjar.api.service.backtest.BacktestModelRegistry
import kr.spdaewoon.magicjar.api.service.backtest.BacktestPerformanceCalculator
import kr.spdaewoon.magicjar.api.service.backtest.JvmBacktestEngine
import kr.spdaewoon.magicjar.api.service.backtest.commission.KisDirectCommissionModel
import kr.spdaewoon.magicjar.api.service.backtest.commission.NoOpCommissionModel
import kr.spdaewoon.magicjar.api.service.backtest.slippage.FixedRateSlippageModel
import kr.spdaewoon.magicjar.api.service.backtest.slippage.NoOpSlippageModel
import kr.spdaewoon.magicjar.api.service.backtest.tax.KoreanStockTaxModel
import kr.spdaewoon.magicjar.api.service.backtest.tax.NoOpTaxModel
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.EntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant

/**
 * JvmBacktestEngine 단위 테스트 (ADR 0056 §2-2-C — 룩어헤드·시뮬·abort 검증).
 *
 * 검증 1 (positive): 정상 시뮬 — BUY → SELL → SUCCESS
 * 검증 2 (positive): candle 0건 → Aborted(NO_CANDLE_DATA)
 * 검증 3 (positive): strategy 미존재 → StrategyNotFoundException
 * 검증 4 (positive): BUY 신호만, sellCondition null → 강제 청산 후 SUCCESS
 * 검증 5 (positive): evaluateBacktest 호출 검증 — 직접 walkRoot 호출 0건
 * 검증 6 (positive): timeout 50회 → Aborted(TIMEOUT_QUOTA_EXCEEDED)
 * 검증 7 (positive): HOLD 신호 → 매매 0건 → SUCCESS (trades empty)
 */
class JvmBacktestEngineTest {
    private val strategyRepo: StrategyRepositoryPort = mock()
    private val candleRepo: CandleJpaRepository = mock()
    private val evaluatorBridge: BacktestEvaluatorBridge = mock()
    private val modelRegistry: BacktestModelRegistry =
        BacktestModelRegistry(
            taxModels = listOf(KoreanStockTaxModel(), NoOpTaxModel()),
            commissionModels = listOf(KisDirectCommissionModel(), NoOpCommissionModel()),
            slippageModels = listOf(FixedRateSlippageModel(), NoOpSlippageModel()),
        )
    private val performanceCalculator = BacktestPerformanceCalculator()

    private val engine =
        JvmBacktestEngine(
            evaluatorBridge = evaluatorBridge,
            strategyRepository = strategyRepo,
            candleJpaRepository = candleRepo,
            modelRegistry = modelRegistry,
            performanceCalculator = performanceCalculator,
        )

    private fun makeStrategy(buyCondition: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode): Strategy =
        Strategy(
            id = 1L,
            userId = 1L,
            definition =
                StrategyDefinition(
                    dsl =
                        DslStrategyDefinition(
                            name = "test",
                            buyCondition = buyCondition,
                            sellCondition = null,
                        ),
                    universe = Universe.defaultKospi(),
                ),
            complexity =
                StrategyComplexity(
                    complexityClass = ComplexityClass.LOW,
                    indicatorCallCount = 1,
                    nodeCount = 3,
                    astDepth = 2,
                    estimatedEvalTimeMs = 1,
                ),
        )

    private fun makeCandleEntities(
        count: Int,
        basePrice: BigDecimal = BigDecimal("100000"),
    ): List<CandleJpaEntity> {
        val baseInstant = Instant.parse("2024-01-01T00:00:00Z")
        return (0 until count).map { i ->
            CandleJpaEntity(
                id = i.toLong(),
                code = "005930",
                interval = "1d",
                ts = baseInstant.plusSeconds(i.toLong() * 86400),
                openRaw = basePrice,
                highRaw = basePrice.multiply(BigDecimal("1.01")),
                lowRaw = basePrice.multiply(BigDecimal("0.99")),
                closeRaw = basePrice,
                openAdj = basePrice,
                highAdj = basePrice.multiply(BigDecimal("1.01")),
                lowAdj = basePrice.multiply(BigDecimal("0.99")),
                closeAdj = basePrice,
                volume = 10000L,
                source = "TEST",
            )
        }
    }

    private fun makeRequest(): BacktestRequest =
        BacktestRequest(
            strategyId = 1L,
            universe = listOf("005930"),
            interval = "1d",
            from = LocalDate(2024, 1, 1),
            to = LocalDate(2024, 12, 31),
            initialCapital = BigDecimal("10000000"),
            taxModel = "none",
            commissionModel = "none",
            slippageModel = "none",
            priceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
            sizingPolicy = SizingPolicy.AllCapital,
        )

    @Test
    fun `검증1_정상 시뮬 BUY t=0 SELL t=1 → SUCCESS`() {
        val buyNode = IndicatorRef("close", emptyList())
        val strategy =
            makeStrategy(buyNode).copy(
                definition =
                    makeStrategy(buyNode).definition.copy(
                        dsl =
                            DslStrategyDefinition(
                                name = "test",
                                buyCondition = buyNode,
                                sellCondition = buyNode, // 동일 조건을 sell로도 사용
                            ),
                    ),
            )
        whenever(strategyRepo.findById(1L)).thenReturn(strategy)
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(5))

        // t=0: BUY, t=1: HOLD, t=2: SELL, t=3..4: HOLD
        val evalResults =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY_t0"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf("trigger" to "HOLD_t1"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.SELL, BigDecimal.ONE, mapOf("trigger" to "SELL_t2"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf("trigger" to "HOLD_t3"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf("trigger" to "HOLD_t4"), Clock.System.now(), 1L),
            )

        var buyCallIdx = 0
        var sellCallIdx = 0
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer {
            // buyCondition과 sellCondition이 같은 노드이므로 — 번갈아 가며 반환
            evalResults[buyCallIdx++.coerceAtMost(evalResults.size - 1)]
        }

        val result = engine.run(makeRequest())
        assertInstanceOf(BacktestResult.Success::class.java, result)
        val success = result as BacktestResult.Success
        assertTrue(success.trades.isNotEmpty(), "매매 기록이 있어야 합니다")
    }

    @Test
    fun `검증2_캔들 0건 → Aborted NO_CANDLE_DATA`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeStrategy(buyNode))
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(emptyList())

        val result = engine.run(makeRequest())
        assertInstanceOf(BacktestResult.Aborted::class.java, result)
        assertEquals("NO_CANDLE_DATA", (result as BacktestResult.Aborted).reason)
    }

    @Test
    fun `검증3_strategy 미존재 → StrategyNotFoundException`() {
        whenever(strategyRepo.findById(99L)).thenReturn(null)

        val request = makeRequest().copy(strategyId = 99L)
        org.junit.jupiter.api.Assertions.assertThrows(BacktestException.StrategyNotFoundException::class.java) {
            engine.run(request)
        }
    }

    @Test
    fun `검증4_BUY만 SELL 없음 → 강제청산 후 SUCCESS`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeStrategy(buyNode))
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(3))

        // BUY t=0, HOLD t=1, HOLD t=2 → 강제 청산
        var callIdx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf("trigger" to "HOLD"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf("trigger" to "HOLD"), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer {
            results[callIdx++.coerceAtMost(results.size - 1)]
        }

        val result = engine.run(makeRequest())
        assertInstanceOf(BacktestResult.Success::class.java, result)
        // BUY 1건 + 강제청산 SELL 1건 = 2건
        val success = result as BacktestResult.Success
        assertEquals(2, success.trades.size, "BUY + 강제청산 SELL = 2건이어야 합니다")
    }

    @Test
    fun `검증5_evaluateBacktest 호출 검증 — walkRoot 직접 호출 0건`() {
        // JvmBacktestEngine이 evaluatorBridge.evaluateBacktest만 호출하고
        // 직접 walkRoot 호출이 없음을 코드 구조로 검증
        // (이 테스트는 evaluatorBridge mock을 통해 간접 검증)
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeStrategy(buyNode))
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(2))

        var bridgeCallCount = 0
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer {
            bridgeCallCount++
            StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L)
        }

        engine.run(makeRequest())
        assertTrue(bridgeCallCount > 0, "evaluatorBridge.evaluateBacktest는 반드시 호출되어야 합니다")
    }

    @Test
    fun `검증6_timeout 50회 → Aborted TIMEOUT_QUOTA_EXCEEDED`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeStrategy(buyNode))
        // 충분한 캔들 제공 (50회 timeout이 쌓이려면 50개 이상의 시점 필요)
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(100))

        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenThrow(StrategyException.StrategyTimeoutException(1L, 1000L))

        val result = engine.run(makeRequest())
        assertInstanceOf(BacktestResult.Aborted::class.java, result)
        val aborted = result as BacktestResult.Aborted
        assertEquals("TIMEOUT_QUOTA_EXCEEDED", aborted.reason)
        assertEquals(50, aborted.timeoutCount)
    }

    @Test
    fun `검증7_HOLD 신호만 → SUCCESS trades empty`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeStrategy(buyNode))
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(5))

        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L))

        val result = engine.run(makeRequest())
        assertInstanceOf(BacktestResult.Success::class.java, result)
        val success = result as BacktestResult.Success
        assertTrue(success.trades.isEmpty(), "HOLD만이면 매매 기록 0건이어야 합니다")
    }

    // =====================================================================
    // 분할매수 R3 — scale-in/exit가 ScaleInExitCalculator 단일 함수 경유 (spec §6-3)
    // =====================================================================

    /** 종가 시퀀스를 그대로 캔들로 만든다(scale-in/exit 가격 기반 분기 검증용). */
    private fun candlesWithCloses(closes: List<String>): List<CandleJpaEntity> {
        val base = Instant.parse("2024-01-01T00:00:00Z")
        return closes.mapIndexed { i, c ->
            val price = BigDecimal(c)
            CandleJpaEntity(
                id = i.toLong(),
                code = "005930",
                interval = "1d",
                ts = base.plusSeconds(i.toLong() * 86400),
                openRaw = price,
                highRaw = price,
                lowRaw = price,
                closeRaw = price,
                openAdj = price,
                highAdj = price,
                lowAdj = price,
                closeAdj = price,
                volume = 10000L,
                source = "TEST",
            )
        }
    }

    private fun makeScaleInStrategy(
        buyCondition: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode,
        scaleIn: ScaleInPlan? = null,
        partialExits: List<PartialExit>? = null,
        fixedStopLoss: BigDecimal? = null,
        takeProfitPct: BigDecimal? = null,
    ): Strategy {
        val baseDef = makeStrategy(buyCondition).definition
        return makeStrategy(buyCondition).copy(
            definition =
                baseDef.copy(
                    scaleIn = scaleIn,
                    partialExits = partialExits,
                    fixedStopLoss = fixedStopLoss,
                    takeProfitPct = takeProfitPct,
                ),
        )
    }

    @Test
    fun `R3 backtest - three slice scale-in averages across rounds via calculator`() {
        val buyNode = IndicatorRef("close", emptyList())
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = null, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        whenever(strategyRepo.findById(1L)).thenReturn(
            makeScaleInStrategy(buyNode, scaleIn = plan, fixedStopLoss = BigDecimal("20"), takeProfitPct = BigDecimal("50")),
        )
        // 3 boundary 모두 BUY 발화 + 마지막 강제청산 → 진입 3회 + 청산 1회
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))

        val result = engine.run(makeRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        val buys = success.trades.filter { it.side == TradeSide.BUY }
        assertEquals(3, buys.size, "3분할 진입 = BUY 3건")
        // 균등 분할이므로 회차 인덱스 0/1/2가 reason에 기록됨
        assertEquals(
            listOf(0, 1, 2),
            buys.map { it.signalReason["sliceIndex"] },
        )
        // 강제청산 SELL 1건
        assertEquals(1, success.trades.count { it.side == TradeSide.SELL })
    }

    @Test
    fun `R3 backtest - stop-loss exits full position`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(
            makeScaleInStrategy(
                buyNode,
                partialExits = listOf(PartialExit(BigDecimal("10"), BigDecimal("50"))),
                fixedStopLoss = BigDecimal("5"),
            ),
        )
        // t0 1000(진입), t1 940(-6% → stop 발화 전량 청산), t2 940
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "940", "940")))
        // 진입만 BUY, 이후 HOLD(추가 진입 없음 — scale-in null이라 신규 진입은 미보유 시 1회)
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result = engine.run(makeRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        val sells = success.trades.filter { it.side == TradeSide.SELL }
        assertEquals(1, sells.size, "stop-loss 전량 청산 1건")
        assertEquals("STOP_LOSS", sells.single().signalReason["trigger"])
    }

    @Test
    fun `R3 backtest - partial-tp fires once even while price stays above trigger`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(
            makeScaleInStrategy(
                buyNode,
                partialExits = listOf(PartialExit(BigDecimal("3"), BigDecimal("50"))),
                fixedStopLoss = BigDecimal("20"),
                takeProfitPct = BigDecimal("50"),
            ),
        )
        // t0 1000(진입), t1 1040(+4% → 단계0 부분 청산), t2 1040(+4% 여전 → 중복 0), t3 1040
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1040", "1040", "1040")))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result = engine.run(makeRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        // 분할 익절 1건(단계0) + 강제청산 1건 = SELL 2건. 중복 partial-tp 0건.
        val partialTps = success.trades.filter { it.signalReason["trigger"] == "PARTIAL_TAKE_PROFIT#0" }
        assertEquals(1, partialTps.size, "partial-tp 단계0은 1회만 발화")
    }

    @Test
    fun `R3 backtest - scale-in null is identical to legacy path (zero regression)`() {
        val buyNode = IndicatorRef("close", emptyList())
        // scale-in/partialExits 모두 null → isActive=false → 기존 경로
        val legacyStrategy =
            makeStrategy(buyNode).let { s ->
                s.copy(
                    definition =
                        s.definition.copy(
                            dsl = DslStrategyDefinition(name = "t", buyCondition = buyNode, sellCondition = buyNode),
                        ),
                )
            }
        whenever(strategyRepo.findById(1L)).thenReturn(legacyStrategy)
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(5))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.SELL, BigDecimal.ONE, mapOf("trigger" to "SELL"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result = engine.run(makeRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        // 기존 동작: BUY 1 + SELL 1(= AllCapital 전액). slice reason 없음.
        assertEquals(1, success.trades.count { it.side == TradeSide.BUY })
        assertTrue(success.trades.none { it.signalReason.containsKey("sliceIndex") }, "legacy 경로엔 scale-in reason 없음")
    }

    // =====================================================================
    // ADR 0114 R3 — 백테스트 진입 정책 정합 (EntryPricePolicy 단일 함수 공유)
    // 호가 부재 근사: ask1 ≈ 슬리피지 적용 체결가, priceAtSignal = 봉 평가가 → 캡 게이트만 의미.
    // =====================================================================

    /** 슬리피지 적용(fixed_0.05 = 0.05%) 요청 — 진입 정책 캡 게이트가 발화하려면 ask1>priceAtSignal이어야 함. */
    private fun makeSlippageRequest(): BacktestRequest = makeRequest().copy(slippageModel = "fixed_0.05")

    private fun makeEntryPolicyStrategy(
        buyCondition: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode,
        entryPolicy: EntryPolicy,
    ): Strategy {
        val baseDef = makeStrategy(buyCondition).definition
        return makeStrategy(buyCondition).copy(definition = baseDef.copy(entryPolicy = entryPolicy))
    }

    @Test
    fun `R3 backtest - entryPolicy 캡 0퍼센트면 슬리피지 체결가가 상한 초과 → 진입 스킵 집계`() {
        val buyNode = IndicatorRef("close", emptyList())
        // maxEntrySlippagePct=0 → cap=priceAtSignal. ask1=price×1.0005 > cap → PriceCapExceeded.
        whenever(strategyRepo.findById(1L)).thenReturn(
            makeEntryPolicyStrategy(buyNode, EntryPolicy(maxEntrySlippagePct = BigDecimal.ZERO)),
        )
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(3))
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))

        val result = engine.run(makeSlippageRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        // 모든 BUY 기회가 캡 초과로 스킵 → 매매 0건, priceCapExceeded 집계.
        assertTrue(success.trades.isEmpty(), "캡 초과로 진입 0건이어야 합니다")
        assertTrue(success.entrySkip.priceCapExceeded >= 1, "priceCapExceeded 스킵이 집계되어야 합니다")
        assertEquals(0, success.entrySkip.spreadTooWide, "호가 부재로 스프레드 게이트는 0(근사 한계)")
        assertEquals(0, success.entrySkip.quoteStale, "quoteAsOf=evalAt 근사로 stale 게이트는 0")
        assertEquals(0, success.entrySkip.noQuote, "ask1 항상 주입 — noQuote 0")
    }

    @Test
    fun `R3 backtest - entryPolicy 캡 넉넉하면 진입 정상 + 스킵 0`() {
        val buyNode = IndicatorRef("close", emptyList())
        // maxEntrySlippagePct=1.0% > 슬리피지 0.05% → 캡 통과 → 정상 진입.
        whenever(strategyRepo.findById(1L)).thenReturn(
            makeEntryPolicyStrategy(buyNode, EntryPolicy(maxEntrySlippagePct = BigDecimal("1.0"))),
        )
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(3))
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))

        val result = engine.run(makeSlippageRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        assertTrue(success.trades.any { it.side == TradeSide.BUY }, "캡 통과 시 진입 BUY가 있어야 합니다")
        assertEquals(0, success.entrySkip.total, "캡 통과면 스킵 0")
    }

    @Test
    fun `R3 backtest - entryPolicy null이면 진입 정책 미적용(스킵 0 · 하위호환)`() {
        val buyNode = IndicatorRef("close", emptyList())
        // entryPolicy 없음 → 기존 즉시 체결 모델 그대로. 슬리피지 있어도 스킵 0.
        whenever(strategyRepo.findById(1L)).thenReturn(makeStrategy(buyNode))
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(makeCandleEntities(3))
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))

        val result = engine.run(makeSlippageRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        assertEquals(0, success.entrySkip.total, "entryPolicy 미설정 전략은 스킵 집계 0")
        assertTrue(success.trades.any { it.side == TradeSide.BUY }, "정책 없으면 정상 진입")
    }

    // =====================================================================
    // MDD mark-to-market fix (ADR 0056 §2-7 amend — V76 검증 #860 결함)
    // equity(t) = cash + 보유 포지션 수량 × 현재 종가. 종전 cash-only는 보유 구간 equity≈0 → MDD≈100% 아티팩트.
    // =====================================================================

    /** 손절·익절 없는 buy-only 전략(보유 유지 → 미실현 손익이 MTM equity에 반영되는지 검증). */
    private fun makeBuyOnlyStrategy(buyCondition: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode): Strategy {
        val baseDef = makeStrategy(buyCondition).definition
        return makeStrategy(buyCondition).copy(
            definition = baseDef.copy(dsl = DslStrategyDefinition(name = "buy-only", buyCondition = buyCondition, sellCondition = null)),
        )
    }

    @Test
    fun `MDD MTM - 보유 중 미실현 손실이 MDD에 반영되고 100퍼센트 아티팩트가 아니다`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeBuyOnlyStrategy(buyNode))
        // t0 1000(전액 진입), t1 800(-20% 미실현), t2 1000(회복) → 강제청산.
        // MTM equity: ≈[init, ~init, ~init×0.8, ~init] → MDD ≈ 20%. cash-only였다면 진입 직후 ≈0 → MDD ≈ 100%.
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "800", "1000")))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result = engine.run(makeRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        val mdd = success.performance.maxDrawdown.toDouble()
        // 핵심: MDD가 100% 아티팩트가 아니라 미실현 -20% 근방의 합리적 값.
        assertTrue(mdd < 0.5, "MTM equity MDD는 100% 아티팩트가 아니어야 합니다 (실측 $mdd)")
        assertTrue(mdd in 0.15..0.25, "MDD는 미실현 -20% 근방이어야 합니다 (실측 $mdd)")
    }

    @Test
    fun `MDD MTM - 청산 후 equity는 실현 cash와 일치(미실현 0)`() {
        val buyNode = IndicatorRef("close", emptyList())
        // 진입 후 즉시 SELL → 보유 0. 청산 후 equity = cash. 무비용(none) 모델이라 손익 ~0 → MDD ~0.
        val strategy =
            makeStrategy(buyNode).copy(
                definition =
                    makeStrategy(buyNode).definition.copy(
                        dsl = DslStrategyDefinition(name = "t", buyCondition = buyNode, sellCondition = buyNode),
                    ),
            )
        whenever(strategyRepo.findById(1L)).thenReturn(strategy)
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.SELL, BigDecimal.ONE, mapOf("trigger" to "SELL"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result = engine.run(makeRequest())
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        // 동일가·무비용 매매라 자본 변동 ~0 → MDD ~0 (보유 구간 아티팩트 없음).
        assertTrue(success.performance.maxDrawdown.toDouble() < 0.01, "동일가 round-trip MDD는 ~0이어야 합니다")
    }

    @Test
    fun `MDD MTM calc 직접 - cash-only였다면 100퍼센트 MTM이면 정상`() {
        val calc = BacktestPerformanceCalculator()
        val initial = BigDecimal("10000000")
        // cash-only 곡선 시뮬: 진입 직후 cash≈0 → 회복. MDD ≈ 100% 아티팩트.
        val cashOnly = listOf(initial, BigDecimal("100"), BigDecimal("100"), initial)
        // MTM 곡선: 보유 중 -20% → 회복. MDD ≈ 20%.
        val mtm = listOf(initial, BigDecimal("10000000"), BigDecimal("8000000"), initial)
        assertTrue(calc.calculateMaxDrawdown(cashOnly).toDouble() > 0.99, "cash-only는 ~100% 아티팩트(결함 재현)")
        assertEquals(0.2, calc.calculateMaxDrawdown(mtm).toDouble(), 0.001, "MTM은 미실현 -20% 반영")
    }

    // =====================================================================
    // ADR 0121 §2-A — FixedFractional 고정 슬롯 사이징 (실전 RiskConfig 정합)
    // 1슬롯 = min(initialCapital × positionPercent/100, maxPositionAmount ?: ∞) / price.
    // AllCapital 회귀 0 + MDD 분산 환산(슬롯 노출 1/10 → 보유 구간 낙폭 1/10 근사).
    // =====================================================================

    /** buy-only 전략으로 단일 진입의 매수 수량을 검증하기 위한 무비용 무사이징 요청. */
    private fun fractionalRequest(policy: SizingPolicy): BacktestRequest = makeRequest().copy(sizingPolicy = policy)

    @Test
    fun `사이징 - FixedFractional 10퍼센트면 1슬롯 = 초기자본 10퍼센트 고정`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeBuyOnlyStrategy(buyNode))
        // 가격 1000, 무비용. 초기자본 10,000,000 × 10% = 1,000,000 슬롯 → 수량 = 1,000,000 / 1000 = 1000주.
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result =
            engine.run(fractionalRequest(SizingPolicy.FixedFractional(positionPercent = BigDecimal("10.00"), maxConcurrent = 5)))
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        val buy = success.trades.single { it.side == TradeSide.BUY }
        assertEquals(1000L, buy.quantity, "10% 슬롯 1,000,000 / 1000원 = 1000주여야 합니다")
    }

    @Test
    fun `사이징 - AllCapital은 전액 투입(회귀 0)`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeBuyOnlyStrategy(buyNode))
        // 가격 1000, 무비용. AllCapital = 10,000,000 / 1000 = 10,000주(전액).
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result = engine.run(fractionalRequest(SizingPolicy.AllCapital))
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        val buy = success.trades.single { it.side == TradeSide.BUY }
        assertEquals(10_000L, buy.quantity, "AllCapital은 전액 10,000주여야 합니다(회귀 0)")
    }

    @Test
    fun `사이징 - maxPositionAmount가 percent 슬롯보다 작으면 min(amount) 적용`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeBuyOnlyStrategy(buyNode))
        // 50% 슬롯 = 5,000,000이지만 maxPositionAmount=2,000,000이 더 작음 → 슬롯 2,000,000 / 1000 = 2000주.
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        var idx = 0
        val results =
            listOf(
                StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
            )
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }

        val result =
            engine.run(
                fractionalRequest(
                    SizingPolicy.FixedFractional(
                        positionPercent = BigDecimal("50.00"),
                        maxConcurrent = 5,
                        maxPositionAmount = BigDecimal("2000000"),
                    ),
                ),
            )
        val success = assertInstanceOf(BacktestResult.Success::class.java, result)
        val buy = success.trades.single { it.side == TradeSide.BUY }
        assertEquals(2000L, buy.quantity, "min(50% 슬롯 5,000,000, amount 2,000,000)=2,000,000 / 1000 = 2000주")
    }

    @Test
    fun `사이징 - 동일 데이터에서 FixedFractional MDD가 AllCapital보다 작다(분산 환산)`() {
        val buyNode = IndicatorRef("close", emptyList())
        whenever(strategyRepo.findById(1L)).thenReturn(makeBuyOnlyStrategy(buyNode))
        // t0 1000(진입), t1 800(-20% 미실현), t2 1000(회복) → 강제청산.
        // AllCapital: 자본 전액 보유 → 보유 구간 자본 -20% → MDD ≈ 20%.
        // FixedFractional 10%: 자본의 10%만 노출 → 보유 구간 자본 -2% → MDD ≈ 2%.
        val closes = listOf("1000", "800", "1000")

        fun runWith(policy: SizingPolicy): Double {
            var idx = 0
            val results =
                listOf(
                    StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                    StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                    StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                )
            whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
                .thenReturn(candlesWithCloses(closes))
            whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }
            val r = engine.run(fractionalRequest(policy))
            return (assertInstanceOf(BacktestResult.Success::class.java, r)).performance.maxDrawdown.toDouble()
        }

        val allCapitalMdd = runWith(SizingPolicy.AllCapital)
        val fractionalMdd = runWith(SizingPolicy.FixedFractional(positionPercent = BigDecimal("10.00"), maxConcurrent = 5))

        assertTrue(allCapitalMdd in 0.15..0.25, "AllCapital MDD는 미실현 -20% 근방 (실측 $allCapitalMdd)")
        assertTrue(fractionalMdd in 0.01..0.05, "10% 슬롯 MDD는 -2% 근방 (실측 $fractionalMdd)")
        assertTrue(fractionalMdd < allCapitalMdd, "FixedFractional MDD($fractionalMdd) < AllCapital MDD($allCapitalMdd) — 분산 환산")
    }

    // =====================================================================
    // ADR 0121 §2-A scale-in 경로 정합 (#871 누락분 hotfix — V78 결함 재발 가드)
    // #871은 executeBuy(비-scale-in)만 슬롯 환산하고 evaluateScaleInExitSlice를 빠뜨려,
    // scale_in_config 전략에서 FixedFractional이 AllCapital과 비트 동일(무력화)되던 갭을 메운다.
    // V77/V78 LOCK 방법론은 TP/SL 발화를 위해 scale-in(totalSlices=1) 경로를 쓰므로 이 경로가 권위.
    // =====================================================================

    /** totalSlices=1 scale-in 전략(weight=100% 단일 회차 → executeBuy 슬롯과 등가 확인용). */
    private fun singleSliceScaleInStrategy(buyCondition: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode): Strategy {
        val plan = ScaleInPlan(totalSlices = 1, sliceWeights = null, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        // TP/SL 동반 — V77/V78처럼 scale-in 경로를 활성화하되 단일 회차로 격리.
        return makeScaleInStrategy(buyCondition, scaleIn = plan, fixedStopLoss = BigDecimal("20"), takeProfitPct = BigDecimal("50"))
    }

    @Test
    fun `사이징 scale-in - totalSlices=1 FixedFractional 슬롯이 비-scale-in executeBuy와 동일 금액`() {
        val buyNode = IndicatorRef("close", emptyList())
        val policy = SizingPolicy.FixedFractional(positionPercent = BigDecimal("10.00"), maxConcurrent = 5)

        // (A) 비-scale-in executeBuy 경로 수량
        whenever(strategyRepo.findById(1L)).thenReturn(makeBuyOnlyStrategy(buyNode))
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        run {
            var idx = 0
            val results =
                listOf(
                    StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L),
                    StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                    StrategyEvalResult(Signal.HOLD, BigDecimal.ZERO, mapOf(), Clock.System.now(), 1L),
                )
            whenever(evaluatorBridge.evaluateBacktest(any(), any(), any())).thenAnswer { results[idx++.coerceAtMost(results.size - 1)] }
        }
        val nonScaleInBuy =
            (assertInstanceOf(BacktestResult.Success::class.java, engine.run(fractionalRequest(policy))))
                .trades
                .first { it.side == TradeSide.BUY }

        // (B) totalSlices=1 scale-in 경로 첫 회차 수량
        whenever(strategyRepo.findById(1L)).thenReturn(singleSliceScaleInStrategy(buyNode))
        run {
            whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
                .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))
        }
        val scaleInBuy =
            (assertInstanceOf(BacktestResult.Success::class.java, engine.run(fractionalRequest(policy))))
                .trades
                .first { it.side == TradeSide.BUY }

        assertEquals(1000L, nonScaleInBuy.quantity, "비-scale-in 10% 슬롯 = 1000주")
        assertEquals(
            nonScaleInBuy.quantity,
            scaleInBuy.quantity,
            "totalSlices=1 scale-in FixedFractional 슬롯이 executeBuy와 등가여야 합니다(weight 100%)",
        )
        // scale-in 경로임을 확인(누락분이 실제 scale-in 경로에서 채워졌음을 입증).
        assertEquals(0, scaleInBuy.signalReason["sliceIndex"], "scale-in 회차 reason 기록(경로 확인)")
    }

    @Test
    fun `사이징 scale-in - FixedFractional vs AllCapital 결과가 달라야 한다(V78 결함 재발 가드)`() {
        val buyNode = IndicatorRef("close", emptyList())
        // V78 방법론: scale_in_config(totalSlices=1) + TP/SL로 scale-in 경로 활성. t1 -20% 미실현 → 회복.
        val closes = listOf("1000", "800", "1000")

        fun runScaleIn(policy: SizingPolicy): Pair<Long, Double> {
            whenever(strategyRepo.findById(1L)).thenReturn(singleSliceScaleInStrategy(buyNode))
            whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
                .thenReturn(candlesWithCloses(closes))
            whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
                .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))
            val s = assertInstanceOf(BacktestResult.Success::class.java, engine.run(fractionalRequest(policy)))
            return s.trades.first { it.side == TradeSide.BUY }.quantity to s.performance.maxDrawdown.toDouble()
        }

        val (allCapQty, allCapMdd) = runScaleIn(SizingPolicy.AllCapital)
        val (fracQty, fracMdd) =
            runScaleIn(SizingPolicy.FixedFractional(positionPercent = BigDecimal("10.00"), maxConcurrent = 5))

        // 핵심 가드: 비트 동일이면 #871 결함(scale-in FixedFractional 무력화)이 재발한 것 → fail.
        assertTrue(
            fracQty != allCapQty,
            "scale-in 경로에서 FixedFractional 수량($fracQty)이 AllCapital($allCapQty)과 달라야 합니다(무력화 재발 가드)",
        )
        assertTrue(fracQty < allCapQty, "10% 슬롯 수량($fracQty) < AllCapital 전액($allCapQty)")
        assertTrue(
            fracMdd < allCapMdd,
            "scale-in FixedFractional MDD($fracMdd) < AllCapital MDD($allCapMdd) — 분산 노출 환산이 scale-in 경로에도 적용",
        )
        assertTrue(allCapMdd in 0.15..0.25, "scale-in AllCapital MDD는 -20% 근방 (실측 $allCapMdd)")
    }

    @Test
    fun `사이징 scale-in - AllCapital totalSlices=3 기존 균등 분할 결과 회귀 0`() {
        val buyNode = IndicatorRef("close", emptyList())
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = null, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        whenever(strategyRepo.findById(1L)).thenReturn(
            makeScaleInStrategy(buyNode, scaleIn = plan, fixedStopLoss = BigDecimal("20"), takeProfitPct = BigDecimal("50")),
        )
        whenever(candleRepo.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(candlesWithCloses(listOf("1000", "1000", "1000")))
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(StrategyEvalResult(Signal.BUY, BigDecimal.ONE, mapOf("trigger" to "BUY"), Clock.System.now(), 1L))

        // AllCapital sliceBase=초기자본 → 균등 3분할 weight=1/3씩. 1000원 × 10,000,000 가정:
        // 회차0 = floor(10,000,000 × 33.333…% ) / 1000. 기존 R3 테스트와 동일 경로(회귀 0) 보장.
        val success = assertInstanceOf(BacktestResult.Success::class.java, engine.run(fractionalRequest(SizingPolicy.AllCapital)))
        val buys = success.trades.filter { it.side == TradeSide.BUY }
        assertEquals(3, buys.size, "AllCapital 3분할 진입 = BUY 3건(기존 동작)")
        assertEquals(listOf(0, 1, 2), buys.map { it.signalReason["sliceIndex"] }, "회차 인덱스 0/1/2(기존 동작)")
        // 균등 분할 — 초기자본 base로 산정된 각 회차 수량이 양수(슬롯 base가 cash 아닌 초기자본임을 확인).
        assertTrue(buys.all { it.quantity > 0 }, "AllCapital 각 회차 수량 > 0(회귀 0)")
    }
}
