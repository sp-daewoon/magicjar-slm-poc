package kr.spdaewoon.magicjar.api.service.backtest

import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 백테스트 성과 지표 계산기 (ADR 0056 §2-7 LOCK).
 *
 * ### 계산식 (ADR 0056 §2-7 + pr-47-w3-t-bt-design.md §7 정합)
 * - totalReturn = (finalCapital - initialCapital) / initialCapital
 * - CAGR = (finalCapital / initialCapital)^(1/years) - 1, years = days / 365.25
 * - MDD = max((peak_at_t - equity[t]) / peak_at_t for all t)
 * - Sharpe = (mean - risk_free/252) / stdev * sqrt(252), n<30 시 null
 * - winRate = winning_trades / closed_trades
 * - profitFactor = sum(win) / sum(abs(loss)), sum(loss)==0 시 null
 * - tradingFrequency = trades.size / business_days
 */
@Component
class BacktestPerformanceCalculator {
    companion object {
        /** 연간 무위험 이자율 2.5% (한국 국채 3년물 기준) — ADR 0056 §2-7 */
        private val ANNUAL_RISK_FREE_RATE = BigDecimal("0.025")

        /** 연간 거래일 252일 */
        private const val TRADING_DAYS_PER_YEAR = 252

        /** Sharpe 계산 최소 데이터 포인트 */
        private const val MIN_SHARPE_DATA_POINTS = 30

        private val ZERO = BigDecimal.ZERO
        private val ONE = BigDecimal.ONE
        private val MC = MathContext(10, RoundingMode.HALF_EVEN)
    }

    /**
     * 성과 지표를 계산한다.
     *
     * @param trades 전체 매매 기록
     * @param initialCapital 초기 자본
     * @param finalCapital 최종 자본
     * @param equityCurve 자본 곡선 (매 거래 후 자본 시계열)
     * @param periodDays 시뮬레이션 기간 (일수)
     * @param businessDays KRX 영업일 수
     */
    fun calculate(
        trades: List<BacktestTrade>,
        initialCapital: BigDecimal,
        finalCapital: BigDecimal,
        equityCurve: List<BigDecimal>,
        periodDays: Long,
        businessDays: Int,
    ): BacktestPerformance {
        val totalReturn = calculateTotalReturn(initialCapital, finalCapital)
        val cagr = calculateCagr(initialCapital, finalCapital, periodDays)
        val maxDrawdown = calculateMaxDrawdown(equityCurve)
        val sharpeRatio = calculateSharpe(equityCurve)

        // 매도 거래만 손익 분석 (BUY-SELL 쌍 기준)
        val sellTrades = trades.filter { it.side == TradeSide.SELL }
        val totalTrades = sellTrades.size
        val winningTrades = sellTrades.count { it.tax.add(it.commission).compareTo(ZERO) >= 0 }

        // 실제 손익 계산 — trade.signalReason에서 realizedPnl 추출 또는 별도 계산
        val pnlList = computePnlList(trades, initialCapital)
        val wins = pnlList.filter { it > ZERO }
        val losses = pnlList.filter { it < ZERO }

        val winCount = wins.size
        val lossCount = losses.size
        val winRate =
            if (pnlList.isNotEmpty()) {
                BigDecimal(winCount).divide(BigDecimal(pnlList.size), 4, RoundingMode.HALF_EVEN)
            } else {
                ZERO
            }

        val avgWin =
            if (wins.isNotEmpty()) {
                wins
                    .reduce { a, b ->
                        a.add(
                            b,
                        )
                    }.divide(BigDecimal(wins.size), 0, RoundingMode.HALF_UP)
            } else {
                ZERO
            }
        val avgLoss =
            if (losses.isNotEmpty()) {
                losses
                    .map { it.abs() }
                    .reduce {
                            a,
                            b,
                        ->
                        a.add(b)
                    }.divide(BigDecimal(losses.size), 0, RoundingMode.HALF_UP)
            } else {
                ZERO
            }

        val sumWin = wins.fold(ZERO) { acc, v -> acc.add(v) }
        val sumLoss = losses.map { it.abs() }.fold(ZERO) { acc, v -> acc.add(v) }
        val profitFactor =
            if (sumLoss.compareTo(ZERO) != 0) {
                sumWin.divide(sumLoss, 4, RoundingMode.HALF_EVEN)
            } else {
                null
            }

        val tradingFrequency =
            if (businessDays > 0) {
                BigDecimal(trades.size).divide(BigDecimal(businessDays), 4, RoundingMode.HALF_EVEN)
            } else {
                ZERO
            }

        return BacktestPerformance(
            totalReturn = totalReturn,
            cagr = cagr,
            maxDrawdown = maxDrawdown,
            sharpeRatio = sharpeRatio,
            winRate = winRate,
            totalTrades = totalTrades,
            winningTrades = winCount,
            losingTrades = lossCount,
            avgWin = avgWin,
            avgLoss = avgLoss,
            profitFactor = profitFactor,
            tradingFrequency = tradingFrequency,
        )
    }

    private fun calculateTotalReturn(
        initialCapital: BigDecimal,
        finalCapital: BigDecimal,
    ): BigDecimal {
        if (initialCapital.compareTo(ZERO) == 0) return ZERO
        return finalCapital
            .subtract(initialCapital)
            .divide(initialCapital, 4, RoundingMode.HALF_EVEN)
    }

    private fun calculateCagr(
        initialCapital: BigDecimal,
        finalCapital: BigDecimal,
        periodDays: Long,
    ): BigDecimal {
        if (initialCapital.compareTo(ZERO) == 0 || periodDays <= 0) return ZERO
        val years = periodDays.toDouble() / 365.25
        if (years <= 0.0) return ZERO

        val ratio = finalCapital.divide(initialCapital, MC).toDouble()
        if (ratio <= 0.0) return ZERO

        val cagrDouble = ratio.pow(1.0 / years) - 1.0
        return BigDecimal(cagrDouble).setScale(4, RoundingMode.HALF_EVEN)
    }

    /**
     * 최대 낙폭(MDD) 계산 — design.md §7-3 정합.
     */
    fun calculateMaxDrawdown(equityCurve: List<BigDecimal>): BigDecimal {
        if (equityCurve.size < 2) return ZERO

        var peak = equityCurve[0]
        var maxDrawdown = ZERO

        for (equity in equityCurve) {
            if (equity > peak) peak = equity
            if (peak.compareTo(ZERO) != 0) {
                val drawdown = peak.subtract(equity).divide(peak, 4, RoundingMode.HALF_EVEN)
                if (drawdown > maxDrawdown) maxDrawdown = drawdown
            }
        }
        return maxDrawdown
    }

    /**
     * Sharpe Ratio 계산 — design.md §7-4 정합.
     * n < 30 시 null 반환.
     */
    fun calculateSharpe(equityCurve: List<BigDecimal>): BigDecimal? {
        if (equityCurve.size < MIN_SHARPE_DATA_POINTS + 1) return null

        val dailyReturns = mutableListOf<Double>()
        for (i in 1 until equityCurve.size) {
            val prev = equityCurve[i - 1].toDouble()
            if (prev != 0.0) {
                dailyReturns.add((equityCurve[i].toDouble() - prev) / prev)
            }
        }
        if (dailyReturns.size < MIN_SHARPE_DATA_POINTS) return null

        val mean = dailyReturns.average()
        val variance = dailyReturns.map { (it - mean).pow(2) }.average()
        val stdev = sqrt(variance)

        if (stdev == 0.0) return null

        val riskFreeDaily = ANNUAL_RISK_FREE_RATE.toDouble() / TRADING_DAYS_PER_YEAR
        val sharpe = (mean - riskFreeDaily) / stdev * sqrt(TRADING_DAYS_PER_YEAR.toDouble())

        return BigDecimal(sharpe).setScale(4, RoundingMode.HALF_EVEN)
    }

    /**
     * 거래별 손익 목록을 계산한다 — SELL 거래에서 실현 손익 추출.
     *
     * 간단한 방식: SELL 거래의 signalReason에서 realizedPnl을 추출하거나,
     * equity curve 차분으로 근사.
     */
    private fun computePnlList(
        trades: List<BacktestTrade>,
        initialCapital: BigDecimal,
    ): List<BigDecimal> {
        // signalReason에 realizedPnl 키가 있으면 사용
        return trades
            .filter { it.side == TradeSide.SELL }
            .mapNotNull { trade ->
                val raw = trade.signalReason["realizedPnl"]
                when (raw) {
                    is BigDecimal -> raw
                    is Number -> BigDecimal(raw.toString())
                    else -> null
                }
            }
    }
}
