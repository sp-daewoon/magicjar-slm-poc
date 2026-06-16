package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 평가 metric ring buffer (ADR 0047 §2-6).
 *
 * - 마지막 N=100건 in-memory ring buffer per strategyId
 * - DB persist는 후속 StrategyEvalMetricRepository 합류 시 본 빈을 교체
 * - 현재는 ring buffer만 — 별도 retention cron으로 DB sync는 post-MVP
 *
 * outcome 코드:
 * - OK · TIMEOUT · CPU_EXCEEDED · UNKNOWN_SYMBOL · SYNTAX_ERROR
 *
 * spec §6-3 LOCK.
 */
@Component
class StrategyEvalMetricRecorder {
    private val log = LoggerFactory.getLogger(javaClass)

    /** strategyId → 최근 N=100 outcome 기록 (in-memory). */
    private val buffer: MutableMap<Long, ArrayDeque<MetricSample>> = ConcurrentHashMap()

    /**
     * 정상 평가 결과 기록.
     */
    fun record(
        strategyId: Long,
        result: StrategyEvalResult,
        cpuTimeMs: Long? = null,
    ) {
        record(
            strategyId = strategyId,
            outcome = "OK",
            evalTimeMs = result.evalTimeMs,
            cpuTimeMs = cpuTimeMs,
            errorMsg = null,
        )
    }

    /**
     * 실패·예외 기록 (timeout · CPU 초과 · syntax 등).
     */
    fun recordFailure(
        strategyId: Long,
        outcome: String,
        evalTimeMs: Long,
        cpuTimeMs: Long? = null,
        errorMsg: String? = null,
    ) {
        record(strategyId, outcome, evalTimeMs, cpuTimeMs, errorMsg)
    }

    private fun record(
        strategyId: Long,
        outcome: String,
        evalTimeMs: Long,
        cpuTimeMs: Long?,
        errorMsg: String?,
    ) {
        val deque = buffer.computeIfAbsent(strategyId) { ArrayDeque() }
        synchronized(deque) {
            if (deque.size >= MAX_RING) deque.removeFirst()
            deque.addLast(
                MetricSample(
                    outcome = outcome,
                    evalTimeMs = evalTimeMs,
                    cpuTimeMs = cpuTimeMs,
                    errorMsg = errorMsg,
                ),
            )
        }
        if (outcome != "OK") {
            log.warn(
                "Strategy eval metric recorded: strategyId={} outcome={} evalTimeMs={} cpuTimeMs={} error={}",
                strategyId,
                outcome,
                evalTimeMs,
                cpuTimeMs,
                errorMsg,
            )
        }
    }

    /**
     * 마지막 N건 조회 (디버깅 / endpoint 합류 후 노출).
     */
    fun lastN(
        strategyId: Long,
        n: Int = MAX_RING,
    ): List<MetricSample> {
        val deque = buffer[strategyId] ?: return emptyList()
        return synchronized(deque) {
            deque.takeLast(n).toList()
        }
    }

    data class MetricSample(
        val outcome: String,
        val evalTimeMs: Long,
        val cpuTimeMs: Long?,
        val errorMsg: String?,
    )

    companion object {
        const val MAX_RING = 100
    }
}
