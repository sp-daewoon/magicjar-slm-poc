package kr.spdaewoon.magicjar.domain.risk.port

import kr.spdaewoon.magicjar.domain.risk.RiskRule

/**
 * signal.risk_status 컬럼 갱신 port (ADR 0067 §5-5 + F-1 정합). 헌법 §6 abstract만.
 *
 * Phase 2 backend-core가 V19 컬럼 ALTER TABLE signal ADD risk_status 후 JPA adapter 구현.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface SignalRiskStatusUpdater {
    fun markAllowed(signalId: Long)

    fun markBlocked(
        signalId: Long,
        rule: RiskRule,
        reason: String,
    )
}
