package kr.spdaewoon.magicjar.domain.reconcile

import java.math.BigDecimal

/**
 * 종목별 KIS ↔ 내부 잔고 diff 결과 (ADR 0070 §2-2 + §2-10 임계치 정합).
 *
 * 자본 사고 검출 룰 (BalanceReconciler):
 * - 수량 diff (kisQty - internalQty) ≥ 1주 OR ≤ -1주 → [isDrift] = true (KillSwitch auto-ON 트리거)
 * - 평균단가 diff 비율 ≥ 0.5% → [isPriceDrift] = true (audit only)
 * - 종목 자체 누락 (KIS=N, internal=0 또는 KIS=0, internal=N) → 수량 diff와 동일 처리
 *
 * @param symbol 종목 코드 (pdno 6자리)
 * @param kisQty KIS 보유 수량
 * @param internalQty DB 내부 position 수량
 * @param qtyDiff kisQty - internalQty
 * @param kisAvgPrice KIS pchs_avg_pric
 * @param internalAvgPrice DB position.avg_price
 * @param avgPriceDiffRatio 평균단가 차이 비율 (KIS 대비, |delta|/kisAvg). KIS=0이면 0.
 * @param isDrift 자본 사고 임계 (수량 차이) 도달 여부
 * @param isPriceDrift 평균단가 임계 (0.5%) 도달 여부 (audit only)
 */
data class HoldingDiff(
    val symbol: String,
    val kisQty: Int,
    val internalQty: Int,
    val qtyDiff: Int,
    val kisAvgPrice: BigDecimal,
    val internalAvgPrice: BigDecimal,
    val avgPriceDiffRatio: BigDecimal,
    val isDrift: Boolean,
    val isPriceDrift: Boolean,
)
