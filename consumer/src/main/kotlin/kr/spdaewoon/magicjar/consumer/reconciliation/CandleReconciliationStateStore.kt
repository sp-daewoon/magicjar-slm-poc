package kr.spdaewoon.magicjar.consumer.reconciliation

import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime candle reconciliation state.
 *
 * Backfill은 비동기로 돌고, 자동매매 평가는 candle boundary thread에서 발생한다.
 * 같은 JVM 안에서는 이 store를 통해 RECONCILING/FAILED 상태를 먼저 보고 fail-closed 한다.
 */
@Component
class CandleReconciliationStateStore {
    enum class Status {
        READY,
        RECONCILING,
        FAILED,
    }

    data class State(
        val status: Status,
        val reason: String,
        val updatedAt: Instant,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun markReconciling(
        code: String,
        interval: CandleInterval,
        reason: String,
    ) {
        states[key(code, interval)] =
            State(
                status = Status.RECONCILING,
                reason = reason,
                updatedAt = Instant.now(),
            )
    }

    fun markReady(
        code: String,
        interval: CandleInterval,
        reason: String,
    ) {
        states[key(code, interval)] =
            State(
                status = Status.READY,
                reason = reason,
                updatedAt = Instant.now(),
            )
    }

    fun markFailed(
        code: String,
        interval: CandleInterval,
        reason: String,
    ) {
        states[key(code, interval)] =
            State(
                status = Status.FAILED,
                reason = reason,
                updatedAt = Instant.now(),
            )
    }

    fun stateOf(
        code: String,
        interval: CandleInterval,
    ): State? = states[key(code, interval)]

    private fun key(
        code: String,
        interval: CandleInterval,
    ): String = "$code:${interval.code}"
}
