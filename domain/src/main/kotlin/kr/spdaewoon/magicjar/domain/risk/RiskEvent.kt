package kr.spdaewoon.magicjar.domain.risk

import kotlinx.datetime.Instant

/**
 * Risk 평가 이벤트 도메인 (ADR 0067 §5-2 + G-1 채택).
 *
 * BLOCKED만 영속 권장 (ADR 0067 §9-1) — signal.risk_status 컬럼으로 ALLOWED cross-ref 가능.
 *
 * spec 인용: ADR 0067 §5-2.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
data class RiskEvent(
    val id: Long? = null,
    val userId: String,
    val signalId: Long? = null,
    val strategyId: Long? = null,
    val rule: RiskRule,
    val verdict: RiskVerdict,
    val reason: String,
    val contextJson: String? = null,
    val createdAt: Instant,
)
