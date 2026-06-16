package kr.spdaewoon.magicjar.api.service.performance

import kr.spdaewoon.magicjar.domain.performance.EquityPoint
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceCalculator
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Performance 계산 구현체 — ADR 0072 §9-3 + spec §3-2 MDD 알고리즘 LOCK.
 *
 * 5 지표 pure math (sum·avg·count·max — KIS/AI 호출 0):
 * 1. [totalPnl]    : sum(pnlDaily.realizedPnl)
 * 2. [winRate]     : sum(winCount) / sum(tradeCount) * 100 (체결 기준)
 * 3. [profitLossRatio]: avg(avgProfit) / abs(avg(avgLoss)) — 손실 0건 시 null
 * 4. [maxDrawdown] : equity curve peak-trough 알고리즘 (spec §3-2 LOCK)
 * 5. [buildEquityCurve]: pnlDaily → EquityPoint 시계열 (누적 P&L 순서 정합)
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 * // KIS 호출 0 — 모든 데이터 영속 reuse (ADR 0072 §1-4 LOCK)
 */
@Component
class PerformanceCalculatorImpl : PerformanceCalculator {
    /**
     * 총 실현 손익 합산.
     * sum(pnlDaily.realizedPnl) — 일별 P&L 합산.
     */
    override fun totalPnl(pnlDailyList: List<PnlDaily>): BigDecimal =
        pnlDailyList.fold(BigDecimal.ZERO) { acc, pnl -> acc + pnl.realizedPnl }

    /**
     * 승률 계산.
     * sum(winCount) / sum(tradeCount) * 100
     * 체결 0건 시 0.0 반환.
     * 반환: % NUMERIC(5,2) HALF_UP.
     */
    override fun winRate(pnlDailyList: List<PnlDaily>): BigDecimal {
        val totalTrades = pnlDailyList.sumOf { it.tradeCount }
        if (totalTrades == 0) return BigDecimal.ZERO
        val totalWins = pnlDailyList.sumOf { it.winCount }
        return BigDecimal(totalWins)
            .divide(BigDecimal(totalTrades), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
            .setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * 평균 손익비 avg(avgProfit) / abs(avg(avgLoss)).
     * 손실 거래 0건 시 null 반환.
     * 수익 거래 0건 시 0.0 반환.
     * 반환: NUMERIC(8,4) HALF_UP.
     */
    override fun profitLossRatio(pnlDailyList: List<PnlDaily>): BigDecimal? {
        // 수익 거래 평균: avgProfit이 있는 day들의 가중 평균 (winCount 기반)
        val profitDays = pnlDailyList.filter { it.winCount > 0 && (it.avgProfit ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val lossDays = pnlDailyList.filter { it.lossCount > 0 && (it.avgLoss ?: BigDecimal.ZERO) < BigDecimal.ZERO }

        if (lossDays.isEmpty()) return null

        val avgProfit =
            if (profitDays.isEmpty()) {
                BigDecimal.ZERO
            } else {
                val totalWinCount = profitDays.sumOf { it.winCount }
                val weightedProfitSum =
                    profitDays.fold(BigDecimal.ZERO) { acc, day ->
                        val p = day.avgProfit ?: BigDecimal.ZERO
                        acc + (p * BigDecimal(day.winCount))
                    }
                if (totalWinCount > 0) {
                    weightedProfitSum.divide(BigDecimal(totalWinCount), 8, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }
            }

        val totalLossCount = lossDays.sumOf { it.lossCount }
        val weightedLossSum =
            lossDays.fold(BigDecimal.ZERO) { acc, day ->
                val l = day.avgLoss ?: BigDecimal.ZERO
                acc + (l * BigDecimal(day.lossCount))
            }
        val avgLoss = weightedLossSum.divide(BigDecimal(totalLossCount), 8, RoundingMode.HALF_UP).abs()

        if (avgLoss.signum() == 0) return null

        return avgProfit.divide(avgLoss, 4, RoundingMode.HALF_UP)
    }

    /**
     * 최대 낙폭 (MDD) 계산 — spec §3-2 알고리즘 LOCK.
     *
     * peak-trough 방식:
     * - peak 갱신: point.cumulativePnl > peak 시 peak 갱신
     * - drawdown: (peak - point.cumulativePnl) / peak * 100
     * - peak 양수 한정 (음수 equity 시 0% — 음수 기준 MDD는 의미 없음)
     *
     * 반환: % NUMERIC(8,4) HALF_UP.
     */
    override fun maxDrawdown(equityCurve: List<EquityPoint>): BigDecimal {
        if (equityCurve.isEmpty()) return BigDecimal.ZERO
        var peak = equityCurve[0].cumulativePnl
        var maxDd = BigDecimal.ZERO
        for (point in equityCurve) {
            if (point.cumulativePnl > peak) peak = point.cumulativePnl
            if (peak.signum() > 0) {
                val drawdown =
                    (peak - point.cumulativePnl)
                        .divide(peak, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100))
                if (drawdown > maxDd) maxDd = drawdown
            }
        }
        return maxDd.setScale(4, RoundingMode.HALF_UP)
    }

    /**
     * Equity Curve 시계열 생성.
     * pnlDailyList (tradeDate 오름차순 가정) → [EquityPoint] 리스트.
     * 누적 P&L은 running sum.
     * from~to 범위 내 pnlDaily가 없는 날짜는 포함 안 함 (거래 없는 날 생략).
     */
    override fun buildEquityCurve(
        pnlDailyList: List<PnlDaily>,
        from: LocalDate,
        to: LocalDate,
    ): List<EquityPoint> {
        val sorted =
            pnlDailyList
                .filter { it.tradeDate in from..to }
                .sortedBy { it.tradeDate }
        var cumulative = BigDecimal.ZERO
        return sorted.map { pnl ->
            cumulative += pnl.realizedPnl
            EquityPoint(
                tradeDate = pnl.tradeDate,
                dailyPnl = pnl.realizedPnl,
                cumulativePnl = cumulative,
                tradeCount = pnl.tradeCount,
            )
        }
    }
}
