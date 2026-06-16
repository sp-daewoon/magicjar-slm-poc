package kr.spdaewoon.magicjar.domain.performance

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Equity Curve 점 도메인 엔티티 — ADR 0072 §9-3 Phase 2 산출물.
 *
 * 일별 누적 P&L 시계열 점. equity curve 차트 source.
 *
 * - [tradeDate]: 거래일
 * - [dailyPnl]: 당일 실현 손익 (원)
 * - [cumulativePnl]: 누적 손익 (equity) (원)
 * - [tradeCount]: 당일 거래 횟수
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
data class EquityPoint(
    val tradeDate: LocalDate,
    val dailyPnl: BigDecimal,
    val cumulativePnl: BigDecimal,
    val tradeCount: Int,
)
