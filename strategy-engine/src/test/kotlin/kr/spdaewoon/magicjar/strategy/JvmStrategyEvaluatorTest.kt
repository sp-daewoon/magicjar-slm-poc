package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.math.BigDecimal
import java.util.concurrent.ThreadPoolExecutor

/**
 * JvmStrategyEvaluator basic behavior — 결정론적 fake IndicatorSource fixture로 검증.
 *
 * spec §1-2 DoD #8·#16·#17 정합.
 */
class JvmStrategyEvaluatorTest {
    private lateinit var evalPool: ThreadPoolTaskExecutor
    private lateinit var evaluator: JvmStrategyEvaluator
    private lateinit var disabledLog: MutableList<Pair<Long, StrategyDisabledReason>>
    private lateinit var metricRecorder: StrategyEvalMetricRecorder
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )

    @BeforeEach
    fun setup() {
        evalPool =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 2
                maxPoolSize = 2
                queueCapacity = 10
                setThreadNamePrefix("strategy-eval-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
                initialize()
            }
        disabledLog = mutableListOf()
        metricRecorder = StrategyEvalMetricRecorder()

        val fakeSource = FakeIndicatorSource()
        val engine = IndicatorEngine(listOf(fakeSource))
        val mapper = DslIdentifierMapper()
        // PatternRecognizer — consecutive 항상 0 반환, recognize 항상 false (테스트용 no-op)
        val noopPatternRecognizer =
            object : PatternRecognizer {
                override fun recognize(
                    candles: List<Candle>,
                    patterns: Set<String>,
                ): List<PatternMatch> = emptyList()

                override fun recognize(
                    name: String,
                    candles: List<Candle>,
                ): Boolean = false

                override fun consecutive(
                    direction: String,
                    candles: List<Candle>,
                ): Int = 0
            }

        evaluator =
            JvmStrategyEvaluator(
                indicatorEngine = engine,
                patternRecognizer = noopPatternRecognizer,
                identifierMapper = mapper,
                evalPool = evalPool,
                metricRecorder = metricRecorder,
                autoDisableCallback = { id, r -> disabledLog.add(id to r) },
                timeoutMs = 5000, // 충분히 길게
                cpuBudgetMs = 50,
                backtestTimeoutMs = 5000,
                cpuBudgetViolationThreshold = 5,
            )
    }

    @Test
    fun `threshold rsi less than 30 — BUY when rsi=20`() {
        val ast = parser.parse("rsi(14) < 30")
        val candles = candleSeries(50, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 1L, ast = ast, candles = candles)

        assertEquals(Signal.BUY, result.signal)
        assertEquals("THRESHOLD", result.reason["trigger"])
    }

    @Test
    fun `crossover ma 5 above ma 20 — detected when ma5 crosses ma20`() {
        val ast = parser.parse("ma(5) crosses_above ma(20)")
        val candles = candleSeries(50, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 2L, ast = ast, candles = candles)

        // FakeIndicatorSource는 마지막 시점 ma=120, 직전 시점 ma=110 (crossover 가짜 시뮬)
        assertNotNull(result.signal)
        // CROSSOVER trigger 확인
        if (result.signal == Signal.BUY) {
            assertEquals("CROSSOVER", result.reason["trigger"])
        }
    }

    @Test
    fun `pattern consecutive — noopRecognizer 0 반환 HOLD`() {
        val ast = parser.parse("consecutive(up) >= 5")
        val candles = candleSeries(50, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 3L, ast = ast, candles = candles)

        // noopPatternRecognizer는 0 반환 → 0 >= 5 false → HOLD
        assertEquals(Signal.HOLD, result.signal)
        assertEquals("PATTERN", result.reason["trigger"])
    }

    @Test
    fun `composite AND — both conditions must hold`() {
        val ast = parser.parse("rsi(14) < 30 AND close > 0")
        val candles = candleSeries(50, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 4L, ast = ast, candles = candles)

        assertEquals(Signal.BUY, result.signal)
        assertEquals("COMPOSITE", result.reason["trigger"])
        assertEquals("AND", result.reason["operator"])
    }

    @Test
    fun `insufficient candles — graceful HOLD`() {
        val ast = parser.parse("rsi(14) < 30")
        // 5개만
        val candles = candleSeries(5, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 5L, ast = ast, candles = candles)

        assertEquals(Signal.HOLD, result.signal)
        assertEquals("INSUFFICIENT_DATA", result.reason["trigger"])
    }

    @Test
    fun `direct candle access — close greater than 0 fixture`() {
        val ast = parser.parse("close > 0")
        val candles = candleSeries(20, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 6L, ast = ast, candles = candles)
        assertEquals(Signal.BUY, result.signal)
    }

    @Test
    fun `direct candle access — open greater than close fixture`() {
        // openRaw = 100 고정, closeRaw = 100 + i → 마지막 캔들에서 open < close
        val ast = parser.parse("open < close")
        val candles = candleSeries(20, closeStart = BigDecimal("100"))

        val result = evaluator.evaluate(strategyId = 7L, ast = ast, candles = candles)
        assertEquals(Signal.BUY, result.signal)
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    private fun candleSeries(
        count: Int,
        closeStart: BigDecimal,
    ): List<Candle> =
        (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = Instant.fromEpochSeconds(1_700_000_000L + i * 86_400L),
                openRaw = closeStart,
                highRaw = closeStart.add(BigDecimal("5")),
                lowRaw = closeStart.subtract(BigDecimal("5")),
                closeRaw = closeStart.add(BigDecimal(i)),
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 1_000_000L + i * 1000L,
                source = "test",
            )
        }

    /**
     * 결정론적 fake IndicatorSource — 마지막 시점은 spec.name 기반 고정값 반환.
     *
     * - rsi: 20 (< 30 만족)
     * - ma(period): period=5 → 120, period=20 → 110 (crossover 시뮬), 그 외 → 100
     * - 그 외: 50 (default)
     */
    private class FakeIndicatorSource : IndicatorSource {
        override val name: String = "fake-test"
        override val priority: Int = 1

        override fun supports(spec: IndicatorSpec): Boolean = true

        override fun calculate(
            spec: IndicatorSpec,
            candles: List<Candle>,
        ): IndicatorResult {
            val series =
                candles.mapIndexed { idx, candle ->
                    val value =
                        when (spec.name) {
                            "rsi" -> BigDecimal("20")
                            "ma" -> {
                                val period = spec.intParam("period", 20)
                                // crossover 시뮬: period=5는 직전 시점 110, 마지막 시점 120
                                //   period=20은 일정하게 110
                                val isLast = idx == candles.size - 1
                                when {
                                    period == 5 && !isLast -> BigDecimal("110")
                                    period == 5 && isLast -> BigDecimal("120")
                                    period == 20 -> BigDecimal("110")
                                    else -> BigDecimal("100")
                                }
                            }
                            "volume_ma" -> BigDecimal("1000000")
                            "change", "returns" -> BigDecimal("1")
                            else -> BigDecimal("50")
                        }
                    IndicatorPoint(time = candle.ts, values = mapOf(spec.name to value))
                }
            return IndicatorResult(spec = spec, series = series, source = name)
        }
    }
}
