package kr.spdaewoon.magicjar.domain.backtest

import java.math.BigDecimal

/**
 * 백테스트 성과 지표 (ADR 0056 §2-7 계산식 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * ### 계산식 출처 (ADR 0056 §2-7)
 * - totalReturn = (finalCapital - initialCapital) / initialCapital
 * - CAGR = (finalCapital / initialCapital)^(1/years) - 1, years = days / 365.25
 * - MDD = max((peak_at_t - equity[t]) / peak_at_t for all t)
 * - Sharpe = (mean - risk_free/252) / stdev * sqrt(252), n<30 시 null
 * - winRate = winning_trades / closed_trades
 * - profitFactor = sum(win) / sum(abs(loss)), sum(loss)==0 시 null
 * - tradingFrequency = trades.size / business_days
 *
 * @param totalReturn 총수익률 = (finalCapital - initialCapital) / initialCapital
 * @param cagr 연환산 수익률 (Compound Annual Growth Rate)
 * @param maxDrawdown 최대 낙폭 (MDD) — 양수값 (e.g. 0.15 = 15% 손실)
 * @param sharpeRatio Sharpe 비율 (n < 30 시 null)
 * @param winRate 승률 = 이익 거래 / 총 거래
 * @param totalTrades 총 거래 수 (매수+매도 쌍 기준)
 * @param winningTrades 이익 거래 수
 * @param losingTrades 손실 거래 수
 * @param avgWin 평균 이익 금액 (KRW)
 * @param avgLoss 평균 손실 금액 (양수값, KRW)
 * @param profitFactor sumWin / sumLoss (sumLoss==0 시 null)
 * @param tradingFrequency 매매 빈도 = 총 거래 / 영업일수
 */
data class BacktestPerformance(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val maxDrawdown: BigDecimal,
    val sharpeRatio: BigDecimal?,
    val winRate: BigDecimal,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val profitFactor: BigDecimal?,
    val tradingFrequency: BigDecimal,
)
