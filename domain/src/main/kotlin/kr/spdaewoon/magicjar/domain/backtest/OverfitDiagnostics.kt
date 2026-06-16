package kr.spdaewoon.magicjar.domain.backtest

import java.math.BigDecimal

/**
 * 과최적화 진단 결과 — IS/OOS 분할 백테스트 (ADR 0117 §2-6 · spec §3-R3 시그니처 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * ## 무엇을 거르나 (돈 버는 4레버 ④ — 검증 신뢰도)
 * 과거 수익률만 보면 "그 기간에만 맞은 가짜 우위"(과최적화)를 걸러낼 수 없다. 백테스트 기간을
 * **In-Sample(학습 — 앞 구간)** 과 **Out-of-Sample(검증 — 뒤 구간)** 로 분할해 각각 독립 백테스트하고,
 * IS에서 좋던 성과가 OOS에서 무너지면(degradation 큼) 과최적화로 판정한다. 미래 수익으로 직결되는 거름망.
 *
 * ## 판정 기준 LOCK (ADR 0117 §2-6 · spec §3-R3 — 제안서 §3.3 + ADR 0056 정합)
 * `passed = OOS.totalReturn > 0 && OOS.totalTrades >= 100 && OOS.maxDrawdown <= 0.08`.
 * 즉 **검증 구간에서도 기대값이 양(+)이고, 표본이 충분하며(거래≥100), 낙폭이 제어(MDD≤8%)** 될 때만 통과.
 * 이 셋 중 하나라도 실패하면 `passed=false`(과최적화 의심 또는 표본 부족).
 *
 * @param inSample IS(학습) 구간 성과 — from ~ splitDate
 * @param outOfSample OOS(검증) 구간 성과 — splitDate ~ to
 * @param returnDegradation 수익 저하율 = (IS.totalReturn - OOS.totalReturn) / IS.totalReturn. **클수록 과최적화**.
 *   IS.totalReturn == 0 또는 음수면 비율이 무의미 → null([degradationUndefinedReason] 참고). 1.0(=100%) 이상이면 OOS가 IS의 우위를 완전히 상실.
 * @param sharpeDegradation IS/OOS Sharpe 차 (IS.sharpe - OOS.sharpe). 어느 구간이라도 Sharpe가 null(거래 n<30)이면 null.
 * @param passed 판정 LOCK 통과 여부 (OOS 기대값>0 && 거래≥100 && MDD≤8%).
 * @param oosExpectancyPositive OOS 기대값(totalReturn) > 0 — 판정 분해 1.
 * @param oosTradesSufficient OOS 거래수 ≥ 100 — 판정 분해 2.
 * @param oosDrawdownControlled OOS MDD ≤ 8% — 판정 분해 3.
 * @param degradationUndefinedReason returnDegradation이 null인 사유 ("IS_RETURN_NON_POSITIVE") 또는 null(정상 산출).
 */
data class OverfitDiagnostics(
    val inSample: BacktestPerformance,
    val outOfSample: BacktestPerformance,
    val returnDegradation: BigDecimal?,
    val sharpeDegradation: BigDecimal?,
    val passed: Boolean,
    val oosExpectancyPositive: Boolean,
    val oosTradesSufficient: Boolean,
    val oosDrawdownControlled: Boolean,
    val degradationUndefinedReason: String? = null,
) {
    companion object {
        /** OOS 기대값 게이트 임계 — totalReturn > 0 (제안서 §3.3 LOCK). */
        val OOS_MIN_EXPECTANCY: BigDecimal = BigDecimal.ZERO

        /** OOS 표본 충분 게이트 — totalTrades >= 100 (제안서 §3.3 LOCK). */
        const val OOS_MIN_TRADES: Int = 100

        /** OOS 낙폭 제어 게이트 — maxDrawdown <= 0.08 (8%, 제안서 §3.3 LOCK). */
        val OOS_MAX_DRAWDOWN: BigDecimal = BigDecimal("0.08")
    }
}
