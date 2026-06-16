package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * Scenario Replay 전용 OHLCV snapshot.
 *
 * DB candle adapter가 붙기 전까지 domain engine은 이 순수 값만 받는다.
 */
data class ScenarioCandle(
    val code: String,
    val interval: String,
    val openedAt: Instant,
    val closedAt: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
) {
    init {
        require(code.matches(CODE_PATTERN)) { "code must be 6 digits: $code" }
        require(interval.isNotBlank()) { "interval must be non-blank" }
        require(openedAt <= closedAt) { "openedAt must be <= closedAt" }
        require(open > BigDecimal.ZERO) { "open must be positive: $open" }
        require(high > BigDecimal.ZERO) { "high must be positive: $high" }
        require(low > BigDecimal.ZERO) { "low must be positive: $low" }
        require(close > BigDecimal.ZERO) { "close must be positive: $close" }
        require(high >= low) { "high must be >= low: high=$high, low=$low" }
        require(volume >= 0) { "volume must be >= 0: $volume" }
    }

    companion object {
        private val CODE_PATTERN = Regex("\\d{6}")
    }
}
