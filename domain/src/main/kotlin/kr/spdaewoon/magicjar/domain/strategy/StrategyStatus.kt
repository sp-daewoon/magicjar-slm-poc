package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 상태 enum (ADR 0066 §2-6 F-1 채택 + BUY_BLOCKED 보호 상태 확장).
 *
 * 사용자 결정 (2026-05-12): F-1 status enum. 2026-06-02 리스크 자동정지의 청산 보호를 위해 BUY_BLOCKED 확장.
 *
 * ### 4가지 의도
 * - [ACTIVE] — 평가 path 진입 + 신규 BUY 허용 (default · 등록 직후 default)
 * - [BUY_BLOCKED] — 리스크 누적 보호 상태. 평가 path는 유지하되 신규 BUY만 차단하고 SELL/익절/손절은 허용
 * - [PAUSED] — 사용자 토글 — 평가 skip, 즉시 재활성화 가능
 * - [DISABLED] — sandbox auto-disable (ADR 0047 §2-2-D TIMEOUT·CPU_BUDGET_EXCEEDED), 사용자 명시 `[다시 활성화]` 의무
 * - [DELETED] — soft delete — list 조회 제외 (audit 보존)
 *
 * ### transition 매트릭스 (ADR 0066 §2-6 F-1)
 * - 사용자 액션: ACTIVE/BUY_BLOCKED ↔ PAUSED (토글) · ANY → DELETED · DISABLED/BUY_BLOCKED → ACTIVE (사용자 명시 reactivate)
 * - 시스템 액션: ACTIVE → DISABLED (sandbox auto, ADR 0047 §2-2-D), ACTIVE → BUY_BLOCKED (risk auto-pause)
 *
 * ### 기존 자산 정합
 * 기존 [StrategyDisabledReason] (NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL·RISK_VIOLATION)와 별도 axis.
 * status=DISABLED 일 때 disabledReason ∈ {TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL·RISK_VIOLATION} 매트릭스.
 * status=BUY_BLOCKED 일 때 disabledReason=RISK_VIOLATION 정합. status=ACTIVE 일 때 disabledReason=NULL 정합.
 *
 * spec 인용: ADR 0066 §2-6 F-1 + §5-1.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
enum class StrategyStatus {
    ACTIVE,
    BUY_BLOCKED,
    PAUSED,
    DISABLED,
    DELETED,
    ;

    /** 평가 path 진입 가능 여부. BUY_BLOCKED는 SELL/청산 평가를 위해 진입한다. */
    fun isEvaluable(): Boolean = this == ACTIVE || this == BUY_BLOCKED

    /** 신규 BUY 주문 진입 가능 여부. */
    fun allowsBuy(): Boolean = this == ACTIVE
}
