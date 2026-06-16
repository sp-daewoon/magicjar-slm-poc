package kr.spdaewoon.magicjar.domain.reconcile

/**
 * Reconcile 비교 결과 sealed (ADR 0070 §2-2 BalanceReconciler 산출물).
 *
 * 사용처: BalanceReconciler.compare(kisHoldings, internalPositions) → [ReconcileResult]
 *
 * 분기:
 * - [Reconciled]: 모든 종목 일치 (수량·평균단가 모두 임계 내)
 * - [Drifted]: 수량 diff ≥ 1주 또는 종목 누락 검출 — KillSwitch auto-ON 트리거
 * - [PriceDrifted]: 평균단가 diff ≥ 0.5%만 검출 (audit only, KillSwitch toggle X)
 */
sealed class ReconcileResult {
    /** 모든 종목 정합. */
    data class Reconciled(
        val totalHoldings: Int,
    ) : ReconcileResult()

    /** 수량 diff 검출 — 자본 사고. KillSwitch auto-ON. */
    data class Drifted(
        val driftedHoldings: List<HoldingDiff>,
        val priceDriftedHoldings: List<HoldingDiff> = emptyList(),
    ) : ReconcileResult()

    /** 평균단가 diff만 — audit only. */
    data class PriceDrifted(
        val priceDriftedHoldings: List<HoldingDiff>,
    ) : ReconcileResult()
}
