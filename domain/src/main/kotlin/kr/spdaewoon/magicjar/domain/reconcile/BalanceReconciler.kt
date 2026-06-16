package kr.spdaewoon.magicjar.domain.reconcile

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * KIS 잔고 ↔ 내부 DB position diff 검출 도메인 로직 (ADR 0041·0070 §2-2 + §2-10).
 *
 * Spring 의존 0 — 순수 도메인 함수. Test fixture에서 isolated 검증 가능.
 *
 * ### diff 검출 룰 (ADR 0070 §2-10 임계 LOCK)
 * | 조건 | 임계 | 처리 |
 * |---|---|---|
 * | 수량 diff | `abs(kisQty - internalQty) >= 1` | 즉시 drift (KillSwitch auto-ON) |
 * | 평균단가 diff | `abs(kisAvg - internalAvg) / kisAvg >= 0.005` | audit only (DRIFT_PRICE) |
 * | 종목 자체 누락 (KIS=N, internal=0) | (수량 diff와 동일 처리) | drift |
 * | 내부 있는데 KIS=0 (반대) | (수량 diff와 동일 처리) | drift |
 *
 * ### audit only 사유 (평균단가 diff)
 * 재계산 노이즈(소수점 처리·KIS round-down·CorpAction 정합) — 자본 사고 직결 X.
 * backlog §47에 사용자 설정화 의제 (드라이브 임계치 사용자 토글).
 *
 * AI 호출 0 — 룰 기반 순수 함수.
 */
object BalanceReconciler {
    /** 수량 diff 임계 (1주 — ADR 0070 §2-10 LOCK). */
    const val QTY_DIFF_THRESHOLD: Int = 1

    /** 평균단가 diff 비율 임계 (0.5% — ADR 0070 §2-10 LOCK). BigDecimal precision 보존. */
    val AVG_PRICE_DIFF_RATIO_THRESHOLD: BigDecimal = BigDecimal("0.005")

    /**
     * KIS 보유 ↔ 내부 보유 비교 — 종목별 [HoldingDiff] 산출 후 종합 [ReconcileResult].
     *
     * @param kisHoldings KIS REST 응답에서 흡수한 보유 종목 (수량 > 0만 포함 — adapter 단계 필터링)
     * @param internalHoldings DB position.qty > 0 보유 종목
     */
    fun compare(
        kisHoldings: List<ReconcileHolding>,
        internalHoldings: List<InternalHolding>,
    ): ReconcileResult {
        // 종목 코드 union — KIS·내부 양쪽 모두에서 검사 의무 (반대 누락 케이스 cover)
        val kisMap = kisHoldings.associateBy { it.symbol }
        val internalMap = internalHoldings.associateBy { it.symbol }
        val allSymbols = (kisMap.keys + internalMap.keys).distinct()

        val driftedHoldings = mutableListOf<HoldingDiff>()
        val priceDriftedHoldings = mutableListOf<HoldingDiff>()

        for (symbol in allSymbols) {
            val kis = kisMap[symbol]
            val internal = internalMap[symbol]
            val diff = diffFor(symbol, kis, internal)

            when {
                diff.isDrift -> driftedHoldings.add(diff)
                diff.isPriceDrift -> priceDriftedHoldings.add(diff)
                else -> { /* 정합 — skip */ }
            }
        }

        return when {
            driftedHoldings.isNotEmpty() ->
                ReconcileResult.Drifted(
                    driftedHoldings = driftedHoldings,
                    priceDriftedHoldings = priceDriftedHoldings,
                )
            priceDriftedHoldings.isNotEmpty() ->
                ReconcileResult.PriceDrifted(priceDriftedHoldings = priceDriftedHoldings)
            else ->
                ReconcileResult.Reconciled(totalHoldings = allSymbols.size)
        }
    }

    /**
     * 단일 종목 diff 산출 — null 양쪽 또는 한쪽 누락 케이스 모두 cover.
     */
    fun diffFor(
        symbol: String,
        kis: ReconcileHolding?,
        internal: InternalHolding?,
    ): HoldingDiff {
        val kisQty = kis?.quantity ?: 0
        val internalQty = internal?.quantity ?: 0
        val qtyDiff = kisQty - internalQty
        val kisAvg = kis?.avgPrice ?: BigDecimal.ZERO
        val internalAvg = internal?.avgPrice ?: BigDecimal.ZERO

        // 수량 임계: 1주 이상 차이 (KIS=N, internal=0 또는 반대도 cover)
        val isQtyDrift = kotlin.math.abs(qtyDiff) >= QTY_DIFF_THRESHOLD

        // 평균단가 diff 비율 — KIS=0 (= 종목 누락)이면 priceDiff 0 (수량 drift로 cover 됨)
        val priceDiffRatio: BigDecimal =
            if (kisAvg.signum() == 0) {
                BigDecimal.ZERO
            } else {
                (kisAvg - internalAvg).abs().divide(kisAvg, 6, RoundingMode.HALF_UP)
            }

        // priceDrift는 수량 drift 아닌 경우만 의미 — drift 시 audit 노이즈 회피
        val isPriceDrift = !isQtyDrift && priceDiffRatio >= AVG_PRICE_DIFF_RATIO_THRESHOLD

        return HoldingDiff(
            symbol = symbol,
            kisQty = kisQty,
            internalQty = internalQty,
            qtyDiff = qtyDiff,
            kisAvgPrice = kisAvg,
            internalAvgPrice = internalAvg,
            avgPriceDiffRatio = priceDiffRatio,
            isDrift = isQtyDrift,
            isPriceDrift = isPriceDrift,
        )
    }
}
