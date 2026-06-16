package kr.spdaewoon.magicjar.batch.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * batch 모듈용 performance_summary JPA 엔티티 — DailyPerformanceJob UPSERT용.
 *
 * Flyway V30 `performance_summary` 테이블 정합.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Entity
@Table(name = "performance_summary")
class BatchPerformanceSummaryJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "period", nullable = false, length = 8)
    val period: String,
    @Column(name = "period_start", nullable = false)
    val periodStart: LocalDate,
    @Column(name = "period_end", nullable = false)
    val periodEnd: LocalDate,
    @Column(name = "total_pnl", nullable = false, precision = 18, scale = 2)
    val totalPnl: BigDecimal,
    @Column(name = "mdd_pct", nullable = false, precision = 8, scale = 4)
    val mddPct: BigDecimal,
    @Column(name = "win_rate_pct", nullable = false, precision = 5, scale = 2)
    val winRatePct: BigDecimal,
    @Column(name = "profit_loss_ratio", precision = 8, scale = 4)
    val profitLossRatio: BigDecimal?,
    @Column(name = "trade_count", nullable = false)
    val tradeCount: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", nullable = false, columnDefinition = "jsonb")
    val metrics: String = "{}",
    @Column(name = "computed_at", nullable = false)
    val computedAt: Instant = Instant.now(),
)
