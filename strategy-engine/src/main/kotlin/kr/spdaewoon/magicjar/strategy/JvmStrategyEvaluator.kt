package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticExpr
import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticOperator
import kr.spdaewoon.magicjar.domain.strategy.dsl.CompositeCondition
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.LogicalOperator
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JVM 기반 전략 평가기 (ADR 0022 §2-4 + ADR 0047 §2-2 sandbox).
 *
 * Source: spec §6-3 LOCK.
 *
 * ### 책임
 * 1. AST visitor walk — IndicatorRef → DslIdentifierMapper → IndicatorEngine.calculate (ADR 0021 단일 진입점)
 * 2. Condition / CompositeCondition 평가 → Signal (BUY/SELL/HOLD) + reason
 * 3. Sandbox: Future.get(timeout=100ms) + 평가 worker 내부 ThreadMXBean CPU 측정 + auto-disable 콜백
 * 4. 캔들 부족 시 graceful HOLD (insufficient_data)
 * 5. PatternRecognizer domain port 호출 — `@Component(name="patternRecognizer")` JvmPatternRecognizer (PR-39a)
 *
 * ### Sandbox 정책 (ADR 0047 §2-2)
 * - timeout 100ms (realtime) / 1000ms (backtest, [evaluateBacktest])
 * - CPU budget 50ms — ThreadMXBean.getThreadCpuTime
 * - 1회 timeout 발생 시 즉시 [EvaluatorAutoDisableCallback.onAutoDisable] 호출 (TIMEOUT 사유)
 * - CPU budget 초과 N회 (default 5) 누적 시 CPU_BUDGET_EXCEEDED 사유 disable
 *
 * spec §6-3 LOCK.
 */
@Component
class JvmStrategyEvaluator(
    private val indicatorEngine: IndicatorEngine,
    private val patternRecognizer: PatternRecognizer,
    private val identifierMapper: DslIdentifierMapper,
    @Qualifier("strategyEvalPool") private val evalPool: ThreadPoolTaskExecutor,
    private val metricRecorder: StrategyEvalMetricRecorder,
    private val autoDisableCallback: EvaluatorAutoDisableCallback,
    @Value("\${magicjar.dsl.eval.timeout-ms:100}") private val timeoutMs: Long,
    @Value("\${magicjar.dsl.cpu-budget-ms:50}") private val cpuBudgetMs: Long,
    @Value("\${magicjar.dsl.backtest-timeout-ms:1000}") private val backtestTimeoutMs: Long,
    @Value("\${magicjar.dsl.cpu-budget-violation-threshold:5}") private val cpuBudgetViolationThreshold: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** strategyId → CPU budget 누적 위반 횟수 (in-memory). */
    private val cpuViolationCount: MutableMap<Long, Int> = java.util.concurrent.ConcurrentHashMap()

    /**
     * 실시간 평가 (timeout 100ms · sandbox 적용).
     *
     * @param strategyId 전략 ID (auto-disable 콜백용)
     * @param ast 진입 또는 청산 식의 AST root (DSL syntax 검증은 미리 [KisYamlDslParser]가 통과 가정)
     * @param candles 평가 시점까지의 캔들 (시간순 오름차순)
     */
    fun evaluate(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult = submitWithTimeout(strategyId, ast, candles, timeoutMs)

    /**
     * 백테스트 평가 (timeout 1000ms · auto-disable 미적용).
     */
    fun evaluateBacktest(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult = submitWithTimeout(strategyId, ast, candles, backtestTimeoutMs, autoDisable = false)

    private fun submitWithTimeout(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
        timeoutMillis: Long,
        autoDisable: Boolean = true,
    ): StrategyEvalResult {
        val startNs = System.nanoTime()
        val callable =
            Callable {
                val cpuStartNs = currentThreadCpuTimeNs()
                val result = walkRoot(ast, candles)
                val cpuTimeMs =
                    cpuStartNs?.let { start ->
                        currentThreadCpuTimeNs()?.let { end -> (end - start).coerceAtLeast(0L) / 1_000_000 }
                    }
                SandboxEvalResult(result, cpuTimeMs)
            }

        val future =
            try {
                evalPool.submit(callable)
            } catch (e: RejectedExecutionException) {
                val evalTimeMs = (System.nanoTime() - startNs) / 1_000_000
                log.warn("Strategy {} eval rejected because pool is saturated", strategyId)
                metricRecorder.recordFailure(
                    strategyId = strategyId,
                    outcome = "POOL_REJECTED",
                    evalTimeMs = evalTimeMs,
                    errorMsg = "strategy eval pool saturated",
                )
                if (autoDisable) {
                    autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.TIMEOUT)
                }
                throw StrategyException.StrategyTimeoutException(strategyId, timeoutMillis)
            }
        return try {
            val sandboxResult = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val result = sandboxResult.result
            val evalTimeMs = (System.nanoTime() - startNs) / 1_000_000
            metricRecorder.record(strategyId, result.copy(evalTimeMs = evalTimeMs), sandboxResult.cpuTimeMs)

            val cpuTimeMs = sandboxResult.cpuTimeMs
            if (cpuTimeMs != null && cpuTimeMs > cpuBudgetMs) {
                val newCount = cpuViolationCount.merge(strategyId, 1) { a, b -> a + b } ?: 1
                log.warn(
                    "Strategy {} CPU budget violation: cpuTimeMs={} > budget={} (count={}/{})",
                    strategyId,
                    cpuTimeMs,
                    cpuBudgetMs,
                    newCount,
                    cpuBudgetViolationThreshold,
                )
                if (autoDisable && newCount >= cpuBudgetViolationThreshold) {
                    cpuViolationCount.remove(strategyId)
                    autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.CPU_BUDGET_EXCEEDED)
                    metricRecorder.recordFailure(
                        strategyId = strategyId,
                        outcome = "CPU_EXCEEDED",
                        evalTimeMs = evalTimeMs,
                        cpuTimeMs = cpuTimeMs,
                        errorMsg = "CPU budget $cpuBudgetMs ms 초과 ${newCount}회 누적 → auto-disable",
                    )
                }
            }
            result.copy(evalTimeMs = evalTimeMs)
        } catch (e: TimeoutException) {
            future.cancel(true)
            val evalTimeMs = (System.nanoTime() - startNs) / 1_000_000
            log.warn("Strategy {} timeout after {}ms (limit={}ms)", strategyId, evalTimeMs, timeoutMillis)
            metricRecorder.recordFailure(
                strategyId = strategyId,
                outcome = "TIMEOUT",
                evalTimeMs = evalTimeMs,
                errorMsg = "evaluation timeout ${timeoutMillis}ms",
            )
            if (autoDisable) {
                autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.TIMEOUT)
            }
            throw StrategyException.StrategyTimeoutException(strategyId, timeoutMillis)
        } catch (e: java.util.concurrent.ExecutionException) {
            val evalTimeMs = (System.nanoTime() - startNs) / 1_000_000
            val cause = e.cause
            val outcome =
                when (cause) {
                    is StrategyException.UnknownDslSymbolException -> "UNKNOWN_SYMBOL"
                    is StrategyException.DslSyntaxException -> "SYNTAX_ERROR"
                    is StrategyException.InsufficientCandlesException -> "INSUFFICIENT_DATA"
                    is IndicatorException -> "INDICATOR_ERROR"
                    else -> "UNKNOWN_ERROR"
                }
            metricRecorder.recordFailure(
                strategyId = strategyId,
                outcome = outcome,
                evalTimeMs = evalTimeMs,
                errorMsg = cause?.message,
            )
            // unwrap underlying domain exception
            throw cause ?: e
        }
    }

    private data class SandboxEvalResult(
        val result: StrategyEvalResult,
        val cpuTimeMs: Long?,
    )

    private fun currentThreadCpuTimeNs(): Long? {
        return try {
            val mx = ManagementFactory.getThreadMXBean()
            if (!mx.isCurrentThreadCpuTimeSupported) return null
            if (mx.isThreadCpuTimeSupported && !mx.isThreadCpuTimeEnabled) {
                runCatching { mx.isThreadCpuTimeEnabled = true }
            }
            mx.currentThreadCpuTime.takeIf { it >= 0L }
        } catch (_: Throwable) {
            null
        }
    }

    // =====================================================================
    // AST walk — root → BUY/SELL/HOLD
    // =====================================================================

    private fun walkRoot(
        ast: DslNode,
        candles: List<Candle>,
    ): StrategyEvalResult {
        val now = Clock.System.now()
        // 캔들 부족 검사
        val required =
            when (ast) {
                is Condition -> ast.requiredDays()
                is CompositeCondition -> ast.requiredDays()
                else -> 1
            }
        if (candles.size < required) {
            return StrategyEvalResult(
                signal = Signal.HOLD,
                strength = BigDecimal.ZERO,
                reason = mapOf("trigger" to "INSUFFICIENT_DATA", "required" to required, "actual" to candles.size),
                evaluatedAt = now,
                evalTimeMs = 0,
            )
        }

        return try {
            val walked = walk(ast, candles)
            when (walked) {
                is BoolEval -> {
                    if (walked.value) {
                        StrategyEvalResult(
                            signal = Signal.BUY,
                            strength = BigDecimal.ONE,
                            reason = walked.reason,
                            evaluatedAt = now,
                            evalTimeMs = 0,
                        )
                    } else {
                        StrategyEvalResult(
                            signal = Signal.HOLD,
                            strength = BigDecimal.ZERO,
                            reason = walked.reason,
                            evaluatedAt = now,
                            evalTimeMs = 0,
                        )
                    }
                }
                is NumEval -> {
                    // top-level이 숫자 (단순 식) — 0 != true 룰
                    val triggered = walked.value.compareTo(BigDecimal.ZERO) != 0
                    StrategyEvalResult(
                        signal = if (triggered) Signal.BUY else Signal.HOLD,
                        strength = if (triggered) BigDecimal.ONE else BigDecimal.ZERO,
                        reason = mapOf("trigger" to "NUMERIC", "value" to walked.value),
                        evaluatedAt = now,
                        evalTimeMs = 0,
                    )
                }
            }
        } catch (e: StrategyException.InsufficientCandlesException) {
            StrategyEvalResult(
                signal = Signal.HOLD,
                strength = BigDecimal.ZERO,
                reason = mapOf("trigger" to "INSUFFICIENT_DATA", "error" to (e.message ?: "")),
                evaluatedAt = now,
                evalTimeMs = 0,
            )
        }
    }

    // =====================================================================
    // AST visitor (Condition · CompositeCondition · Arithmetic · Indicator · Literal)
    // =====================================================================

    private sealed class EvalValue

    private data class BoolEval(
        val value: Boolean,
        val reason: Map<String, Any>,
    ) : EvalValue()

    private data class NumEval(
        val value: BigDecimal,
        val label: String = "",
    ) : EvalValue()

    private fun walk(
        node: DslNode,
        candles: List<Candle>,
    ): EvalValue =
        when (node) {
            is CompositeCondition -> evalComposite(node, candles)
            is Condition -> evalCondition(node, candles)
            is ArithmeticExpr -> evalArithmetic(node, candles)
            is IndicatorRef -> evalIndicator(node, candles)
            is LiteralValue -> NumEval(node.normalized(), label = node.value.toPlainString())
        }

    private fun evalComposite(
        node: CompositeCondition,
        candles: List<Candle>,
    ): EvalValue {
        val l = walk(node.left, candles)
        val r = walk(node.right, candles)
        if (l !is BoolEval || r !is BoolEval) {
            throw StrategyException.DslSyntaxException(
                "Composite operands must be boolean conditions: ${node.operator} got ${l::class.simpleName}, ${r::class.simpleName}",
            )
        }
        val combined =
            when (node.operator) {
                LogicalOperator.AND -> l.value && r.value
                LogicalOperator.OR -> l.value || r.value
            }
        val reason =
            mapOf(
                "trigger" to "COMPOSITE",
                "operator" to node.operator.name,
                "left" to l.reason,
                "right" to r.reason,
            )
        return BoolEval(combined, reason)
    }

    private fun evalCondition(
        node: Condition,
        candles: List<Candle>,
    ): EvalValue {
        // PATTERN — left = consecutive(...) | pattern(...) — T-PAT 미진입 시 stub 호출
        if (node.conditionType == kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType.PATTERN) {
            return evalPattern(node, candles)
        }
        // CROSSOVER — left가 indicator·right가 indicator 형태에서 마지막·이전 시점 비교
        if (node.conditionType == kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType.CROSSOVER) {
            return evalCrossover(node, candles)
        }
        // BREAKOUT — left가 close 류, right가 high(N)
        if (node.conditionType == kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType.BREAKOUT) {
            return evalBreakout(node, candles)
        }
        // THRESHOLD — 일반 비교
        val lhs = walk(node.left, candles)
        val rhs = walk(node.right, candles)
        if (lhs !is NumEval || rhs !is NumEval) {
            throw StrategyException.DslSyntaxException(
                "Threshold operands must be numeric: ${node.operator} got ${lhs::class.simpleName}, ${rhs::class.simpleName}",
            )
        }
        val cmp = lhs.value.compareTo(rhs.value)
        val triggered =
            when (node.operator) {
                Operator.GT -> cmp > 0
                Operator.LT -> cmp < 0
                Operator.GTE -> cmp >= 0
                Operator.LTE -> cmp <= 0
                Operator.EQ -> cmp == 0
                Operator.NEQ -> cmp != 0
                else ->
                    throw StrategyException.DslSyntaxException(
                        "Unexpected operator in THRESHOLD: ${node.operator}",
                    )
            }
        val reason =
            mapOf(
                "trigger" to "THRESHOLD",
                "operator" to node.operator.symbol,
                "lhs" to lhs.label,
                "lhsValue" to lhs.value,
                "rhs" to rhs.label,
                "rhsValue" to rhs.value,
            )
        return BoolEval(triggered, reason)
    }

    private fun evalPattern(
        node: Condition,
        candles: List<Candle>,
    ): EvalValue {
        val left = node.left
        if (left !is IndicatorRef) {
            return BoolEval(false, mapOf("trigger" to "PATTERN_INVALID_LHS"))
        }
        return when (left.name) {
            "consecutive" -> {
                val direction = left.params.firstOrNull()?.toString() ?: "up"
                val count = patternRecognizer.consecutive(direction, candles)
                val rhs = walk(node.right, candles)
                val target = (rhs as? NumEval)?.value?.toInt() ?: 0
                val triggered =
                    when (node.operator) {
                        Operator.GT -> count > target
                        Operator.LT -> count < target
                        Operator.GTE -> count >= target
                        Operator.LTE -> count <= target
                        Operator.EQ -> count == target
                        Operator.NEQ -> count != target
                        else -> false
                    }
                BoolEval(
                    triggered,
                    mapOf(
                        "trigger" to "PATTERN",
                        "patternType" to "consecutive",
                        "direction" to direction,
                        "count" to count,
                        "operator" to node.operator.symbol,
                        "target" to target,
                    ),
                )
            }
            "pattern", "pattern_detected" -> {
                // T-PAT PR-39: `pattern_detected("name")` magicJar alias — `pattern("name")` 동치 라우팅.
                val patternName = left.params.firstOrNull()?.toString() ?: ""
                val matched = patternRecognizer.recognize(patternName, candles)
                BoolEval(
                    matched,
                    mapOf(
                        "trigger" to "PATTERN",
                        "patternFunction" to left.name,
                        "patternName" to patternName,
                        "matched" to matched,
                    ),
                )
            }
            else -> BoolEval(false, mapOf("trigger" to "PATTERN_UNKNOWN", "name" to left.name))
        }
    }

    private fun evalCrossover(
        node: Condition,
        candles: List<Candle>,
    ): EvalValue {
        // 마지막 시점 비교 vs 직전 시점 비교 — 교차 검출
        if (candles.size < 2) {
            return BoolEval(false, mapOf("trigger" to "CROSSOVER_INSUFFICIENT_DATA"))
        }
        val lastCandles = candles
        val prevCandles = candles.subList(0, candles.size - 1)
        val lhsLast = walk(node.left, lastCandles)
        val rhsLast = walk(node.right, lastCandles)
        val lhsPrev = walk(node.left, prevCandles)
        val rhsPrev = walk(node.right, prevCandles)
        if (lhsLast !is NumEval || rhsLast !is NumEval || lhsPrev !is NumEval || rhsPrev !is NumEval) {
            throw StrategyException.DslSyntaxException("Crossover operands must be numeric")
        }
        val triggered =
            when (node.operator) {
                Operator.CROSSES_ABOVE ->
                    lhsPrev.value.compareTo(rhsPrev.value) <= 0 &&
                        lhsLast.value.compareTo(rhsLast.value) > 0
                Operator.CROSSES_BELOW ->
                    lhsPrev.value.compareTo(rhsPrev.value) >= 0 &&
                        lhsLast.value.compareTo(rhsLast.value) < 0
                else -> false
            }
        return BoolEval(
            triggered,
            mapOf(
                "trigger" to "CROSSOVER",
                "operator" to node.operator.symbol,
                "lhs" to lhsLast.label,
                "rhs" to rhsLast.label,
                "lhsLastValue" to lhsLast.value,
                "rhsLastValue" to rhsLast.value,
                "lhsPrevValue" to lhsPrev.value,
                "rhsPrevValue" to rhsPrev.value,
            ),
        )
    }

    private fun evalBreakout(
        node: Condition,
        candles: List<Candle>,
    ): EvalValue {
        val lhs = walk(node.left, candles)
        val rhs = walk(node.right, candles)
        if (lhs !is NumEval || rhs !is NumEval) {
            throw StrategyException.DslSyntaxException("Breakout operands must be numeric")
        }
        val triggered = lhs.value.compareTo(rhs.value) > 0
        return BoolEval(
            triggered,
            mapOf(
                "trigger" to "BREAKOUT",
                "lhs" to lhs.label,
                "lhsValue" to lhs.value,
                "rhs" to rhs.label,
                "rhsValue" to rhs.value,
            ),
        )
    }

    private fun evalArithmetic(
        node: ArithmeticExpr,
        candles: List<Candle>,
    ): EvalValue {
        val l = walk(node.left, candles)
        val r = walk(node.right, candles)
        if (l !is NumEval || r !is NumEval) {
            throw StrategyException.DslSyntaxException("Arithmetic operands must be numeric")
        }
        val result =
            when (node.operator) {
                ArithmeticOperator.ADD -> l.value.add(r.value)
                ArithmeticOperator.SUB -> l.value.subtract(r.value)
                ArithmeticOperator.MUL -> l.value.multiply(r.value)
                ArithmeticOperator.DIV ->
                    if (r.value.compareTo(BigDecimal.ZERO) == 0) {
                        BigDecimal.ZERO
                    } else {
                        l.value.divide(r.value, 8, RoundingMode.HALF_EVEN)
                    }
            }
        return NumEval(result, label = "(${l.label} ${node.operator.symbol} ${r.label})")
    }

    private fun evalIndicator(
        ref: IndicatorRef,
        candles: List<Candle>,
    ): EvalValue {
        // 캔들 직접 접근 6종 (특수 케이스)
        val direct = directCandleAccess(ref, candles)
        if (direct != null) return direct

        // 패턴 식별자 (단독 사용은 PATTERN으로 분류되므로 여기 도달 불가지만 안전망)
        if (ref.name == "consecutive") {
            val direction = ref.params.firstOrNull()?.toString() ?: "up"
            val count = patternRecognizer.consecutive(direction, candles)
            return NumEval(BigDecimal(count), label = "consecutive($direction)")
        }

        // 자체 함수 4종 — change · highest · lowest · returns (T-IND IndicatorEngine 등록 정합 가정)
        // (등록 안 됐으면 IndicatorException.UnsupportedSpecException 발생)

        val spec = identifierMapper.toIndicatorSpec(ref)
        try {
            val result = indicatorEngine.calculate(spec, candles)
            val value = identifierMapper.extractValue(result, ref)
            return NumEval(value, label = formatRefLabel(ref))
        } catch (e: IndicatorException.InsufficientCandlesException) {
            throw StrategyException.InsufficientCandlesException(0, candles.size)
        }
    }

    /**
     * 캔들 직접 접근 (6종) — open · close · prev_close · high · low · volume.
     */
    private fun directCandleAccess(
        ref: IndicatorRef,
        candles: List<Candle>,
    ): EvalValue? {
        val last = candles.lastOrNull() ?: return null
        return when (ref.name) {
            "open" -> {
                if (ref.params.isEmpty()) NumEval(last.openAdj ?: last.openRaw, label = "open") else null
            }
            "close" -> NumEval(last.closeAdj ?: last.closeRaw, label = "close")
            "prev_close" -> {
                val prev = candles.getOrNull(candles.size - 2) ?: return NumEval(BigDecimal.ZERO, label = "prev_close")
                NumEval(prev.closeAdj ?: prev.closeRaw, label = "prev_close")
            }
            "high" -> {
                // bare `high` (no params) → 마지막 캔들 high
                if (ref.params.isEmpty()) NumEval(last.highAdj ?: last.highRaw, label = "high") else null
            }
            "low" -> {
                if (ref.params.isEmpty()) NumEval(last.lowAdj ?: last.lowRaw, label = "low") else null
            }
            "volume" -> {
                if (ref.params.isEmpty()) NumEval(BigDecimal(last.volume), label = "volume") else null
            }
            else -> null
        }
    }

    private fun formatRefLabel(ref: IndicatorRef): String =
        if (ref.params.isEmpty()) ref.name else "${ref.name}(${ref.params.joinToString(",")})"
}
