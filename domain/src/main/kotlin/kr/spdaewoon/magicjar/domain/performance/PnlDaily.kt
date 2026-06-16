package kr.spdaewoon.magicjar.domain.performance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 일별 P&L 도메인 엔티티 — ADR 0072 §9-3 Phase 2 산출물.
 *
 * Flyway V30 `pnl_daily` 테이블 정합.
 *
 * - [userId]: 사용자 ID (다중 사용자 격리)
 * - [tradeDate]: 거래일
 * - [realizedPnl]: 당일 실현 손익 합산 (원)
 * - [winCount]: 당일 수익 거래 수
 * - [lossCount]: 당일 손실 거래 수
 * - [tradeCount]: 당일 총 체결 거래 수 (winCount + lossCount + break-even)
 * - [avgProfit]: 당일 평균 수익 (원, null = 수익 거래 0건)
 * - [avgLoss]: 당일 평균 손실 (원, null = 손실 거래 0건, 음수)
 * - [equityAtClose]: 장 마감 시점 누적 equity (batch cron 집계)
 *
 * UQ (user_id, trade_date) — batch UPSERT 멱등성.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
data class PnlDaily(
    val id: Long? = null,
    val userId: String,
    val tradeDate: LocalDate,
    val realizedPnl: BigDecimal,
    val winCount: Int,
    val lossCount: Int,
    val tradeCount: Int,
    val avgProfit: BigDecimal?,
    val avgLoss: BigDecimal?,
    val equityAtClose: BigDecimal?,
    val createdAt: Instant = Instant.now(),
)
