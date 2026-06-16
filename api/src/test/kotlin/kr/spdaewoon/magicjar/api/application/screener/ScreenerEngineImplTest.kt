package kr.spdaewoon.magicjar.api.application.screener

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.screener.ScreenerCriteria
import kr.spdaewoon.magicjar.domain.screener.ScreenerFilter
import kr.spdaewoon.magicjar.domain.screener.port.InstrumentLookupPort
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * ScreenerEngineImpl 단위 테스트 (ADR 0071 §2-1·§2-7 + spec §11-1).
 *
 * 검증 시나리오:
 * 1. 정상 흐름 — universe iterate + BUY 매칭 + ranker 호출
 * 2. universe 부재 — empty result + skip 0
 * 3. Strategy 미존재 — empty result
 * 4. userId 격리 (multi-tenant 위반 차단)
 * 5. Strategy disabled — empty result
 * 6. candle 결손 graceful skip — CandleMissing label
 * 7. candle 부족 — CandleInsufficient label
 * 8. DSL 평가 timeout — EvalTimeout label
 * 9. DSL 평가 error — EvalError label
 * 10. Phase 2 의존 bean 부재 — graceful empty result (ObjectProvider 패턴)
 */
class ScreenerEngineImplTest {
    private lateinit var ranker: MultiCriteriaRanker
    private lateinit var fakeEvaluator: FakeStrategyEvaluatorPort
    private lateinit var strategyRepo: InMemoryStrategyRepository
    private lateinit var instrumentLookup: InMemoryInstrumentLookup
    private lateinit var candleRepo: InMemoryCandleRepository

    @BeforeEach
    fun setup() {
        ranker = MultiCriteriaRanker()
        fakeEvaluator = FakeStrategyEvaluatorPort()
        strategyRepo = InMemoryStrategyRepository()
        instrumentLookup = InMemoryInstrumentLookup()
        candleRepo = InMemoryCandleRepository()
    }

    @Test
    fun `정상 흐름 — universe iterate + BUY 매칭 + ranker 호출`() {
        // given
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        instrumentLookup.put(
            instrument("005930", "삼성전자"),
            instrument("035720", "카카오"),
        )
        candleRepo.put("005930", buildCandles("005930", 30))
        candleRepo.put("035720", buildCandles("035720", 30))

        fakeEvaluator.signal = Signal.BUY
        fakeEvaluator.strength = BigDecimal("0.85")

        val engine = newEngine()

        // when
        val result = engine.screen(criteria(userId, strategyId))

        // then
        assertEquals(2, result.universeSize)
        assertEquals(2, result.evaluatedCount)
        assertEquals(2, result.matchedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(2, result.matches.size)
        // ranker가 rank 1-base 부여
        assertEquals(1, result.matches[0].rank)
        assertEquals(2, result.matches[1].rank)
        assertEquals(false, result.cacheHit)
        assertEquals("", result.cacheKey)
    }

    @Test
    fun `universe 부재 — empty result + skip 0`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        // instrumentLookup 비어있음

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(0, result.universeSize)
        assertEquals(0, result.matchedCount)
        assertEquals(0, result.skippedCount)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `Strategy 미존재 — empty result`() {
        val userId = 1L
        // strategyRepo 비어있음
        instrumentLookup.put(instrument("005930", "삼성전자"))

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId = 999L))

        assertEquals(0, result.universeSize)
        assertEquals(0, result.matchedCount)
    }

    @Test
    fun `userId 격리 — strategy_userId mismatch 시 empty result (multi-tenant)`() {
        val strategyId = 42L
        // strategy는 userId=1, 조회는 userId=2
        strategyRepo.put(buildStrategy(strategyId, userId = 1L))
        instrumentLookup.put(instrument("005930", "삼성전자"))

        val engine = newEngine()
        val result = engine.screen(criteria(userId = 2L, strategyId = strategyId))

        assertEquals(0, result.universeSize)
        assertEquals(0, result.matchedCount)
    }

    @Test
    fun `Strategy disabled — empty result`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(
            buildStrategy(strategyId, userId).copy(
                disabledReason = StrategyDisabledReason.TIMEOUT,
            ),
        )
        instrumentLookup.put(instrument("005930", "삼성전자"))

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(0, result.universeSize)
        assertEquals(0, result.matchedCount)
    }

    @Test
    fun `candle 결손 graceful skip — CandleMissing label 매트릭스 누적`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        instrumentLookup.put(
            instrument("005930", "삼성전자"),
            instrument("099999", "신규상장"), // candle 0건
        )
        candleRepo.put("005930", buildCandles("005930", 30))
        // 099999 candle 미수집

        fakeEvaluator.signal = Signal.BUY

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(2, result.universeSize)
        assertEquals(1, result.evaluatedCount) // 005930만 평가
        assertEquals(1, result.matchedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(1, result.skippedReasons["캔들 미수집"])
    }

    @Test
    fun `candle 부족 skip — CandleInsufficient label 매트릭스 누적`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        instrumentLookup.put(instrument("005930", "삼성전자"))
        candleRepo.put("005930", buildCandles("005930", 5)) // 5 < minCandles(20)

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(1, result.universeSize)
        assertEquals(0, result.evaluatedCount)
        assertEquals(0, result.matchedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(1, result.skippedReasons["캔들 부족"])
    }

    @Test
    fun `DSL 평가 timeout — EvalTimeout label 매트릭스 누적`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        instrumentLookup.put(instrument("005930", "삼성전자"))
        candleRepo.put("005930", buildCandles("005930", 30))

        fakeEvaluator.forceTimeout = true

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(1, result.universeSize)
        assertEquals(1, result.evaluatedCount)
        assertEquals(0, result.matchedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(1, result.skippedReasons["DSL 평가 timeout"])
    }

    @Test
    fun `DSL 평가 error — EvalError label 매트릭스 누적`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        instrumentLookup.put(instrument("005930", "삼성전자"))
        candleRepo.put("005930", buildCandles("005930", 30))

        fakeEvaluator.forceError = "ATR(14) undefined"

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(1, result.universeSize)
        assertEquals(1, result.evaluatedCount)
        assertEquals(0, result.matchedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(1, result.skippedReasons["DSL 평가 오류"])
    }

    @Test
    fun `Phase 2 의존 bean 부재 — graceful empty result (ObjectProvider 패턴)`() {
        // strategyRepository provider만 있음 — instrumentLookup·candleRepo provider 부재 시
        val engine =
            ScreenerEngineImpl(
                strategyLookupProvider = providerOf(strategyRepo),
                instrumentLookupProvider = emptyProvider(),
                candleRepositoryProvider = emptyProvider(),
                strategyEvaluatorPort = fakeEvaluator,
                ranker = ranker,
                candleLookbackDays = 90,
                minCandles = 20,
            )

        val result = engine.screen(criteria(userId = 1L, strategyId = 42L))

        assertEquals(0, result.universeSize)
        assertEquals(0, result.matchedCount)
    }

    @Test
    fun `HOLD 시그널 — 매칭 미발생 + evaluatedCount만 합류`() {
        val strategyId = 42L
        val userId = 1L
        strategyRepo.put(buildStrategy(strategyId, userId))
        instrumentLookup.put(instrument("005930", "삼성전자"))
        candleRepo.put("005930", buildCandles("005930", 30))

        fakeEvaluator.signal = Signal.HOLD

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(1, result.universeSize)
        assertEquals(1, result.evaluatedCount)
        assertEquals(0, result.matchedCount) // HOLD는 매칭 미발생
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `Strategy buyCondition 부재 — empty result + universe 평가 0`() {
        val strategyId = 42L
        val userId = 1L
        val strategy =
            buildStrategy(strategyId, userId).copy(
                definition =
                    StrategyDefinition(
                        dsl =
                            DslStrategyDefinition(name = "no-buy", buyCondition = null),
                    ),
            )
        strategyRepo.put(strategy)
        instrumentLookup.put(instrument("005930", "삼성전자"))
        candleRepo.put("005930", buildCandles("005930", 30))

        val engine = newEngine()
        val result = engine.screen(criteria(userId, strategyId))

        assertEquals(0, result.universeSize) // 평가 진입 차단
        assertEquals(0, result.matchedCount)
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun newEngine(): ScreenerEngineImpl =
        ScreenerEngineImpl(
            strategyLookupProvider = providerOf(strategyRepo),
            instrumentLookupProvider = providerOf(instrumentLookup),
            candleRepositoryProvider = providerOf(candleRepo),
            strategyEvaluatorPort = fakeEvaluator,
            ranker = ranker,
            candleLookbackDays = 90,
            minCandles = 20,
        )

    private fun criteria(
        userId: Long,
        strategyId: Long,
    ): ScreenerCriteria =
        ScreenerCriteria(
            userId = userId,
            strategyId = strategyId,
            filter = ScreenerFilter.DEFAULT,
            interval = CandleInterval.ONE_DAY,
            limit = 50,
        )

    private fun buildStrategy(
        id: Long,
        userId: Long,
    ): Strategy {
        val ast: DslNode =
            Condition(
                conditionType = ConditionType.THRESHOLD,
                left = IndicatorRef(name = "rsi", params = listOf(BigDecimal("14"))),
                operator = Operator.LT,
                right = LiteralValue(value = BigDecimal("30")),
            )
        return Strategy(
            id = id,
            userId = userId,
            definition =
                StrategyDefinition(
                    dsl =
                        DslStrategyDefinition(
                            name = "test-strategy",
                            buyCondition = ast,
                        ),
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 4,
                    indicatorCallCount = 1,
                    estimatedEvalTimeMs = 4,
                    complexityClass = kr.spdaewoon.magicjar.domain.strategy.ComplexityClass.LOW,
                ),
        )
    }

    private fun instrument(
        code: String,
        name: String,
    ): Instrument =
        Instrument(
            code = code,
            isin = null,
            name = name,
            nameEn = null,
            market = InstrumentMarket.KOSPI,
            type = InstrumentType.STOCK,
            sector = null,
            listedAt = LocalDate(2010, 1, 1),
            delistedAt = null,
            active = true,
        )

    private fun buildCandles(
        code: String,
        count: Int,
    ): List<Candle> {
        val now = Clock.System.now()
        return (0 until count).map { i ->
            Candle(
                code = code,
                interval = CandleInterval.ONE_DAY,
                ts = now.minus(kotlin.time.Duration.parse("${count - i}d")),
                openRaw = BigDecimal("70000"),
                highRaw = BigDecimal("71000"),
                lowRaw = BigDecimal("69500"),
                closeRaw = BigDecimal("70500"),
                openAdj = BigDecimal("70000"),
                highAdj = BigDecimal("71000"),
                lowAdj = BigDecimal("69500"),
                closeAdj = BigDecimal("70500"),
                volume = 1_000_000L,
                source = "TEST",
            )
        }
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        object : ObjectProvider<T> {
            override fun getObject(vararg args: Any?): T = value

            override fun getObject(): T = value

            override fun getIfAvailable(): T = value

            override fun getIfUnique(): T = value
        }

    private fun <T : Any> emptyProvider(): ObjectProvider<T> =
        object : ObjectProvider<T> {
            override fun getObject(vararg args: Any?): T = throw NoSuchElementException()

            override fun getObject(): T = throw NoSuchElementException()

            override fun getIfAvailable(): T? = null

            override fun getIfUnique(): T? = null
        }

    // ────────────────────────────────────────────────────────────────────────
    // fakes
    // ────────────────────────────────────────────────────────────────────────

    /**
     * StrategyEvaluatorPort 테스트용 fake — JvmStrategyEvaluator 의존 우회.
     * timeout/error는 예외로 표현 (ScreenerEngineImpl이 try-catch로 처리).
     */
    private class FakeStrategyEvaluatorPort : StrategyEvaluatorPort {
        var signal: Signal = Signal.HOLD
        var strength: BigDecimal = BigDecimal.ZERO
        var forceTimeout: Boolean = false
        var forceError: String? = null

        override fun evaluateBacktest(
            strategyId: Long,
            ast: DslNode,
            slice: List<Candle>,
        ): StrategyEvalResult = evaluate(strategyId, ast, slice)

        override fun evaluate(
            strategyId: Long,
            ast: DslNode,
            candles: List<Candle>,
        ): StrategyEvalResult {
            if (forceTimeout) throw StrategyException.StrategyTimeoutException(strategyId, 500L)
            forceError?.let { throw StrategyException.DslSyntaxException(it) }
            return StrategyEvalResult(
                signal = signal,
                strength = strength,
                reason = mapOf("trigger" to "TEST", "signal" to signal.name),
                evaluatedAt = Clock.System.now(),
                evalTimeMs = 10,
            )
        }
    }

    private class InMemoryStrategyRepository : StrategyRepositoryPort {
        private val store: MutableMap<Long, Strategy> = mutableMapOf()

        fun put(strategy: Strategy) {
            store[strategy.id ?: error("Strategy id required")] = strategy
        }

        override fun save(strategy: Strategy): Strategy {
            val id = strategy.id ?: 1L
            val saved = strategy.copy(id = id)
            store[id] = saved
            return saved
        }

        override fun findById(id: Long): Strategy? = store[id]

        override fun findAllByUserId(userId: Long): List<Strategy> = store.values.filter { it.userId == userId }

        override fun deleteById(id: Long) {
            store.remove(id)
        }

        override fun autoDisable(
            strategyId: Long,
            reason: StrategyDisabledReason,
        ) {
            store[strategyId]?.let { store[strategyId] = it.copy(disabledReason = reason) }
        }

        override fun enable(strategyId: Long) {
            store[strategyId]?.let { store[strategyId] = it.copy(disabledReason = StrategyDisabledReason.NULL) }
        }
    }

    private class InMemoryInstrumentLookup : InstrumentLookupPort {
        private val store: MutableList<Instrument> = mutableListOf()

        fun put(vararg instruments: Instrument) {
            store.addAll(instruments)
        }

        override fun findActive(filter: ScreenerFilter): List<Instrument> =
            store.filter { inst ->
                inst.active &&
                    inst.delistedAt == null &&
                    inst.market in filter.markets &&
                    inst.type in filter.types
            }
    }

    private class InMemoryCandleRepository : CandleRepository {
        private val store: MutableMap<Pair<String, CandleInterval>, List<Candle>> = mutableMapOf()

        fun put(
            code: String,
            candles: List<Candle>,
        ) {
            store[code to CandleInterval.ONE_DAY] = candles
        }

        override fun saveAll(candles: List<Candle>): Int = 0

        override fun upsertAll(candles: List<Candle>): Int = 0

        override fun countByCodeAndInterval(
            code: String,
            interval: CandleInterval,
        ): Long = (store[code to interval]?.size ?: 0).toLong()

        override fun countByCodeAndIntervalIn(
            code: String,
            intervals: List<CandleInterval>,
        ): Long = intervals.sumOf { countByCodeAndInterval(code, it) }

        override fun findByCodeAndInterval(
            code: String,
            interval: CandleInterval,
        ): List<Candle> = store[code to interval] ?: emptyList()

        override fun findMaxTsByCodeAndInterval(
            code: String,
            interval: CandleInterval,
        ): java.time.Instant? = null

        override fun findAllTsByCodeAndIntervalBetween(
            code: String,
            interval: CandleInterval,
            from: java.time.Instant,
            to: java.time.Instant,
        ): List<java.time.Instant> = emptyList()
    }
}
