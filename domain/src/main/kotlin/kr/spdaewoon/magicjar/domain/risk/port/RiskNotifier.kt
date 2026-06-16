package kr.spdaewoon.magicjar.domain.risk.port

import kr.spdaewoon.magicjar.domain.risk.RiskEvent

/**
 * Risk 위반·auto-pause STOMP fanout port (ADR 0067 §5-5 + F-1 정합). 헌법 §6 abstract만.
 *
 * Phase 2 backend-core가 `/topic/risk.user.{userId}` STOMP fanout 구현.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskNotifier {
    fun notifyViolation(
        userId: String,
        event: RiskEvent,
    )

    fun notifyAutoPaused(
        userId: String,
        strategyId: Long,
        reason: String,
    )
}
