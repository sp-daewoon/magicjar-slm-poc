package kr.spdaewoon.magicjar.api.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * pnl_daily JPA 엔티티 — Flyway V30 정합 (ADR 0072 §3-2).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 * UQ (user_id, trade_date) — batch DailyPerformanceJob UPSERT 멱등성.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Entity
@Table(name = "pnl_daily")
class PnlDailyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Column(name = "realized_pnl", nullable = false, precision = 18, scale = 2)
    val realizedPnl: BigDecimal,
    @Column(name = "win_count", nullable = false)
    val winCount: Int,
    @Column(name = "loss_count", nullable = false)
    val lossCount: Int,
    @Column(name = "trade_count", nullable = false)
    val tradeCount: Int,
    @Column(name = "avg_profit", precision = 18, scale = 2)
    val avgProfit: BigDecimal?,
    @Column(name = "avg_loss", precision = 18, scale = 2)
    val avgLoss: BigDecimal?,
    @Column(name = "equity_at_close", precision = 18, scale = 2)
    val equityAtClose: BigDecimal?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
