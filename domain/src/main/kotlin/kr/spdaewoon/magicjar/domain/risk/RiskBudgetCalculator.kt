package kr.spdaewoon.magicjar.domain.risk

import kr.spdaewoon.magicjar.domain.position.Position
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Risk 한도 산정 순수 계산기 — **사이징(매수 버짓)·사용량 표시의 단일 진실원** (사용자 결재 2026-06-16).
 *
 * ## 왜 별도 계산기로 추출했나 — "표시값 ≠ 실제판정" 위험 차단
 * 매수 사이징(consumer `RiskGatewayEvaluator.resolveBuyBudget`)과 설정 화면이 보여줄 "지금 한도에 얼마나
 * 가까운가"(api `RiskUsageService`)가 **같은 공식**으로 산출되지 않으면, 화면 표시값과 게이트의 실제 판정이
 * 어긋나(예: 화면은 "여유 100만"인데 실제는 0원이라 BLOCKED) 사용자가 잘못된 판단을 내린다. 두 경로가 호출하는
 * 산식을 1개 함수로 고정해 그 갭을 컴파일 단위에서 제거한다.
 *
 * ## 모듈 위치 — domain (Spring·JPA 0)
 * 본 계산기는 [Position]·[RiskConfig]·[BigDecimal]만 입력으로 받는 순수 함수다(외부 I/O 0, 결정론). consumer
 * (`RiskGatewayEvaluator`)와 api(`RiskUsageService`)가 모두 domain 에 의존하므로 양쪽에서 동일 코드를 호출한다.
 *
 * ## 매수 버짓 공식 (RiskGatewayEvaluator.resolveBuyBudget 와 1:1 — PR #1011)
 * ```
 * 절대한도   = config.maxPositionAmount
 * 비율한도   = currentCapital(순자산) × config.maxPositionPercent / 100   (소수점 버림)
 * 총한도     = min(절대한도, 비율한도)                                      ← A-1 규약
 * 전체노출   = Σ over currentPositions { avgPrice × quantity }            ← 전 종목·전 전략, 매수원가
 * 남은한도   = (총한도 − 전체노출).coerceAtLeast(0)
 * 매수가능   = min(남은한도, orderableCash(예수금))
 * ```
 * 예수금은 노출을 다시 차감하지 않는다(이미 현금 잔액). 남은한도와 예수금 중 작은 값이 매수가능이다.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
object RiskBudgetCalculator {
    /**
     * 계좌 전체 노출 — **전 종목·전 전략 매수원가 합** (사용자 결재 2026-06-16).
     *
     * `Σ over positions(quantity>0) { avgPrice × quantity }`. 평가액(현재가)이 아니라 매수원가(avgPrice)를 쓰는 이유는
     * RiskGatewayEvaluator.totalExposure 주석 참조(투입 원금 기준 한도 — 시세 등락에 한도가 출렁이지 않게).
     */
    fun totalExposure(positions: List<Position>): BigDecimal =
        positions
            .filter { it.quantity > 0 }
            .fold(BigDecimal.ZERO) { acc, position ->
                acc + position.avgPrice.multiply(BigDecimal(position.quantity))
            }

    /**
     * 매수 버짓 산정 — RiskGatewayEvaluator.resolveBuyBudget 와 동일 산식(단일 진실원).
     *
     * @param config           사용자 risk 설정 (절대·비율 한도 출처)
     * @param currentCapital   순자산(netAssetValue) — 비율 한도 기준
     * @param orderableCash    주문가능 예수금(cashD2 fallback cashBalance) — 매수가능 cap
     * @param positions        현재 보유 포지션 (전체노출 산정 입력)
     */
    fun resolveBuyBudget(
        config: RiskConfig,
        currentCapital: BigDecimal,
        orderableCash: BigDecimal,
        positions: List<Position>,
    ): BuyBudget {
        val cash = orderableCash.coerceAtLeast(BigDecimal.ZERO)
        val absoluteLimit = config.maxPositionAmount
        val percentLimit =
            currentCapital
                .multiply(config.maxPositionPercent)
                .divide(BigDecimal("100"), 0, RoundingMode.DOWN)
        // 총한도 = min(절대, 순자산×비율) — A-1 규약.
        val totalLimit = absoluteLimit.min(percentLimit)
        val totalExposure = totalExposure(positions)
        val remainingLimit = (totalLimit - totalExposure).coerceAtLeast(BigDecimal.ZERO)
        // 매수가능 = min(남은한도, 예수금). 예수금은 노출을 다시 차감하지 않는다.
        val buyable = remainingLimit.min(cash)
        return BuyBudget(
            orderableCash = cash,
            absoluteLimit = absoluteLimit,
            percentLimit = percentLimit,
            currentCapital = currentCapital,
            totalLimit = totalLimit,
            totalExposure = totalExposure,
            remainingLimit = remainingLimit,
            buyable = buyable,
        )
    }

    /**
     * 일일 손실 한도까지 남은 여유 — `(한도 + 당일실현손익).coerceAtLeast(0)`.
     *
     * 당일실현손익([todayRealizedPnl])은 음수가 손실이다. 한도 도달(손실이 한도 이상)이면 0.
     * - amount 한도: `(dailyLossLimitAmount + todayRealizedPnl).coerceAtLeast(0)`.
     * - percent 한도: 한도금액 = `currentCapital × dailyLossLimitPercent / 100` 으로 환산 후 동일 식.
     * - DLL 비활성(둘 다 null) 또는 percent 한도인데 currentCapital≤0(환산 불가) → null(여유 미산정).
     *
     * @return 남은 여유(원). DLL 비활성·환산 불가 시 null.
     */
    fun dailyLossRemaining(
        config: RiskConfig,
        currentCapital: BigDecimal,
        todayRealizedPnl: BigDecimal,
    ): BigDecimal? {
        val amountLimit = config.dailyLossLimitAmount
        val percentLimit = config.dailyLossLimitPercent
        val effectiveLimit: BigDecimal =
            when {
                amountLimit != null -> amountLimit
                percentLimit != null && currentCapital > BigDecimal.ZERO ->
                    currentCapital
                        .multiply(percentLimit)
                        .divide(BigDecimal("100"), 0, RoundingMode.DOWN)
                else -> return null
            }
        return (effectiveLimit + todayRealizedPnl).coerceAtLeast(BigDecimal.ZERO)
    }

    /**
     * 매수 버짓 산정 결과 (계좌 전체 공유 풀 — RiskGatewayEvaluator.BuyBudget 와 동일 의미).
     */
    data class BuyBudget(
        val orderableCash: BigDecimal,
        val absoluteLimit: BigDecimal,
        val percentLimit: BigDecimal,
        val currentCapital: BigDecimal,
        val totalLimit: BigDecimal,
        val totalExposure: BigDecimal,
        val remainingLimit: BigDecimal,
        val buyable: BigDecimal,
    )
}
