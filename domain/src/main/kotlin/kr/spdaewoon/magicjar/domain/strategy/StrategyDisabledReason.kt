package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 자동 비활성화 사유 (ADR 0047 §2-2-D).
 *
 * - NULL: 정상 활성 (DB nullable column 짝)
 * - TIMEOUT: evaluation timeout 1회 발생 → 자동 disable
 * - CPU_BUDGET_EXCEEDED: CPU budget 50ms 초과 N회 누적
 * - MANUAL: 사용자 수동 비활성
 * - RISK_VIOLATION: risk 위반 연속 N회 → 신규 BUY 차단(BUY_BLOCKED), SELL/청산 평가는 유지
 *
 * spec §6-2 LOCK.
 * ADR 0067 Phase 1 AutoPauseGuard 주석 인용: [RISK_VIOLATION] 신규 추가.
 */
enum class StrategyDisabledReason {
    NULL,
    TIMEOUT,
    CPU_BUDGET_EXCEEDED,
    MANUAL,
    RISK_VIOLATION,
}
