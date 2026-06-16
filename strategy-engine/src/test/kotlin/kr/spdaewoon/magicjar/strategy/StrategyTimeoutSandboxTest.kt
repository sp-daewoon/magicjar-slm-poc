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
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.math.BigDecimal
import java.util.concurrent.ThreadPoolExecutor

/**
 * Sandbox runtime — timeout 100ms · auto-disable 콜백 (ADR 0047 §2-2-A·D).
 *
 * spec §1-2 DoD #9·#10 정합.
 */
class StrategyTimeoutSandboxTest {
    @Test
    fun `slow indicator source triggers timeout — auto-disable callback fired`() {
        val evalPool =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 1
                maxPoolSize = 1
                queueCapacity = 5
                setThreadNamePrefix("strategy-eval-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
                initialize()
            }

        val disabledLog = mutableListOf<Pair<Long, StrategyDisabledReason>>()
        val metricRecorder = StrategyEvalMetricRecorder()

        // 매우 느린 (1000ms sleep) FakeSource
        val slowSource =
            object : IndicatorSource {
                override val name: String = "slow-fake"
                override val priority: Int = 1

                override fun supports(spec: IndicatorSpec): Boolean = true

                override fun calculate(
                    spec: IndicatorSpec,
                    candles: List<Candle>,
                ): IndicatorResult {
                    Thread.sleep(500) // timeout 100ms 초과
                    return IndicatorResult(
                        spec = spec,
                        series =
                            candles.map {
                                IndicatorPoint(time = it.ts, values = mapOf(spec.name to BigDecimal("50")))
                            },
                        source = name,
                    )
                }
            }
        val engine = IndicatorEngine(listOf(slowSource))

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
        val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
        val evaluator =
            JvmStrategyEvaluator(
                indicatorEngine = engine,
                patternRecognizer = noopPatternRecognizer,
                identifierMapper = DslIdentifierMapper(),
                evalPool = evalPool,
                metricRecorder = metricRecorder,
                autoDisableCallback = { id, r -> disabledLog.add(id to r) },
                timeoutMs = 50, // 매우 짧게
                cpuBudgetMs = 50,
                backtestTimeoutMs = 5000,
                cpuBudgetViolationThreshold = 5,
            )

        val ast = parser.parse("rsi(14) < 30")
        val candles =
            (0 until 30).map { i ->
                Candle(
                    code = "005930",
                    interval = CandleInterval.ONE_DAY,
                    ts = Instant.fromEpochSeconds(1_700_000_000L + i * 86_400L),
                    openRaw = BigDecimal("100"),
                    highRaw = BigDecimal("105"),
                    lowRaw = BigDecimal("95"),
                    closeRaw = BigDecimal("100"),
                    openAdj = null,
                    highAdj = null,
                    lowAdj = null,
                    closeAdj = null,
                    volume = 1_000_000L,
                    source = "test",
                )
            }

        // 첫 evaluate 호출 → timeout 예외 + auto-disable 콜백
        val ex =
            assertThrows(StrategyException.StrategyTimeoutException::class.java) {
                evaluator.evaluate(strategyId = 99L, ast = ast, candles = candles)
            }
        assertTrue(ex.message!!.contains("99"))

        // auto-disable 콜백 1회 호출 (TIMEOUT 사유)
        assertEquals(1, disabledLog.size)
        assertEquals(99L, disabledLog[0].first)
        assertEquals(StrategyDisabledReason.TIMEOUT, disabledLog[0].second)

        // metric 기록 — TIMEOUT outcome
        val metrics = metricRecorder.lastN(99L)
        assertTrue(metrics.isNotEmpty(), "Metric should be recorded")
        assertEquals("TIMEOUT", metrics.last().outcome)
    }

    @Test
    fun `cpu budget violation threshold triggers auto-disable callback`() {
        val evalPool =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 1
                maxPoolSize = 1
                queueCapacity = 5
                setThreadNamePrefix("strategy-eval-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
                initialize()
            }

        val disabledLog = mutableListOf<Pair<Long, StrategyDisabledReason>>()
        val metricRecorder = StrategyEvalMetricRecorder()
        val engine = IndicatorEngine(listOf(BusyIndicatorSource()))
        val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)
        val evaluator =
            JvmStrategyEvaluator(
                indicatorEngine = engine,
                patternRecognizer = noopPatternRecognizer(),
                identifierMapper = DslIdentifierMapper(),
                evalPool = evalPool,
                metricRecorder = metricRecorder,
                autoDisableCallback = { id, r -> disabledLog.add(id to r) },
                timeoutMs = 5000,
                cpuBudgetMs = 1,
                backtestTimeoutMs = 5000,
                cpuBudgetViolationThreshold = 1,
            )

        val result = evaluator.evaluate(strategyId = 101L, ast = parser.parse("rsi(14) > 0"), candles = candles(30))

        assertEquals(Signal.BUY, result.signal)
        assertEquals(listOf(101L to StrategyDisabledReason.CPU_BUDGET_EXCEEDED), disabledLog)
        val metrics = metricRecorder.lastN(101L)
        assertTrue(metrics.any { it.outcome == "CPU_EXCEEDED" && (it.cpuTimeMs ?: 0L) > 1L })
    }

    @Test
    fun `pool thread name prefix is strategy-eval`() {
        val pool = StrategyEvalPoolConfig().strategyEvalPool(poolSize = 4, queueSize = 100)
        val nameHolder = mutableListOf<String>()
        pool.execute { nameHolder.add(Thread.currentThread().name) }
        // wait
        Thread.sleep(50)
        assertEquals(1, nameHolder.size)
        assertTrue(nameHolder[0].startsWith("strategy-eval-"))
    }

    @Test
    fun `strategy eval pool rejects saturated work instead of running on caller thread`() {
        val pool = StrategyEvalPoolConfig().strategyEvalPool(poolSize = 1, queueSize = 1)

        assertTrue(pool.threadPoolExecutor.rejectedExecutionHandler is ThreadPoolExecutor.AbortPolicy)
    }

    private fun noopPatternRecognizer(): PatternRecognizer =
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

    private fun candles(count: Int): List<Candle> =
        (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = Instant.fromEpochSeconds(1_700_000_000L + i * 86_400L),
                openRaw = BigDecimal("100"),
                highRaw = BigDecimal("105"),
                lowRaw = BigDecimal("95"),
                closeRaw = BigDecimal("100"),
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 1_000_000L,
                source = "test",
            )
        }

    private class BusyIndicatorSource : IndicatorSource {
        override val name: String = "busy-fake"
        override val priority: Int = 1

        override fun supports(spec: IndicatorSpec): Boolean = true

        override fun calculate(
            spec: IndicatorSpec,
            candles: List<Candle>,
        ): IndicatorResult {
            val deadline = System.nanoTime() + 50_000_000L
            var spin = 0L
            while (System.nanoTime() < deadline) {
                spin += 1L
            }
            val value = BigDecimal((spin % 100L) + 1L)
            return IndicatorResult(
                spec = spec,
                series = candles.map { IndicatorPoint(time = it.ts, values = mapOf(spec.name to value)) },
                source = name,
            )
        }
    }
}
