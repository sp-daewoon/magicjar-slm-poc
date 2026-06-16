package kr.spdaewoon.magicjar.domain.risk

/**
 * RiskGateway 평가 결과 sealed class (ADR 0067 §5-3 + E-1 채택).
 *
 * - [Allowed] — 6 룰 모두 통과 → T-O `order.intent.v1` publish 진입
 * - [Blocked] — 첫 BLOCKED 룰 short-circuit → `risk.violation.v1` publish + STOMP fanout
 *
 * spec 인용: ADR 0067 §5-3 + §7-2.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
sealed class RiskGatewayResult {
    abstract val signalId: Long

    data class Allowed(
        override val signalId: Long,
    ) : RiskGatewayResult()

    data class Blocked(
        override val signalId: Long,
        val rule: RiskRule,
        val reason: String,
        val context: Map<String, Any?> = emptyMap(),
    ) : RiskGatewayResult()
}
