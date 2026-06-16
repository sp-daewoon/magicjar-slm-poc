package kr.spdaewoon.magicjar.domain.reconcile

/**
 * Reconcile 실행 상태 (ADR 0041·0070 §2-2).
 *
 * - RUNNING: 실행 중 (KIS 호출 진행)
 * - RECONCILED: 정상 — 모든 보유 종목 수량·평균단가 일치
 * - DRIFT: 수량 diff ≥ 1주 또는 종목 누락 검출 (자본 사고 — KillSwitch auto-ON 트리거)
 * - DRIFT_PRICE: 평균단가 diff ≥ 0.5% (audit only — KillSwitch toggle X)
 * - ERROR: KIS API 호출 실패·timeout·deserialization 실패 등
 */
enum class ReconcileStatus {
    RUNNING,
    RECONCILED,
    DRIFT,
    DRIFT_PRICE,
    ERROR,
}
