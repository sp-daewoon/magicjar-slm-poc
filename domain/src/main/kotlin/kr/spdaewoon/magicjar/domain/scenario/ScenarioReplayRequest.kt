package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Instant
import java.math.BigDecimal

data class ScenarioReplayRequest(
    val userId: String,
    val strategyId: Long,
    val strategyName: String? = null,
    val code: String,
    val interval: String,
    val from: Instant,
    val to: Instant,
    val initialCash: BigDecimal,
    val initialPositions: List<ScenarioPositionSeed> = emptyList(),
    val fillModel: ScenarioFillModel = ScenarioFillModel.NEXT_CANDLE_OPEN,
    val faults: List<ScenarioFault> = emptyList(),
) {
    init {
        require(userId.isNotBlank()) { "userId must be non-blank" }
        require(strategyId > 0) { "strategyId must be positive: $strategyId" }
        require(code.matches(CODE_PATTERN)) { "code must be 6 digits: $code" }
        require(interval.isNotBlank()) { "interval must be non-blank" }
        require(from <= to) { "from must be <= to: from=$from, to=$to" }
        require(initialCash >= BigDecimal.ZERO) { "initialCash must be >= 0: $initialCash" }
    }

    companion object {
        private val CODE_PATTERN = Regex("\\d{6}")
    }
}

enum class ScenarioFillModel {
    NEXT_CANDLE_OPEN,
}

data class ScenarioFault(
    val at: Instant,
    val type: ScenarioFaultType,
    val message: String? = null,
)

enum class ScenarioFaultType {
    BALANCE_LOOKUP_RATE_LIMITED,
    BALANCE_LOOKUP_FAILED,
    MARKET_CLOSED,
    DATA_NOT_READY,
    STRATEGY_TIMEOUT,
}
