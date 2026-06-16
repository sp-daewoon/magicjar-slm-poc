package kr.spdaewoon.magicjar.domain.strategy

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 전략 평가 결과 (ADR 0022 §2-4).
 *
 * - [signal]: BUY · SELL · HOLD
 * - [strength]: 0.0~1.0 (HOLD 시 0.0)
 * - [reason]: trigger 정보 (예: `{trigger:"CROSSOVER", lhs:"ma(5)", rhs:"ma(20)", lhsValue:1234.56, rhsValue:1230.12}`)
 * - [evaluatedAt]: 평가 완료 시각
 * - [evalTimeMs]: 평가 소요 시간 (ms)
 *
 * spec §6-2 LOCK.
 */
data class StrategyEvalResult(
    val signal: Signal,
    val strength: BigDecimal,
    val reason: Map<String, Any>,
    val evaluatedAt: Instant,
    val evalTimeMs: Long,
)
