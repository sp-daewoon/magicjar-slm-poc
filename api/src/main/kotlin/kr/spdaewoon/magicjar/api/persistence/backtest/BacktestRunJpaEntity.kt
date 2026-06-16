package kr.spdaewoon.magicjar.api.persistence.backtest

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

/**
 * 백테스트 실행 JPA 엔티티 — Flyway V12 (ADR 0061 §3 B-1 채택: 3 테이블 분리).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6 정합: 일반 class (data class 금지 — JPA no-arg 정합).
 *
 * status: PENDING → RUNNING → SUCCEEDED/FAILED
 */
@Entity
@Table(name = "backtest_run")
class BacktestRunJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "strategy_id")
    val strategyId: Long? = null,
    @Column(name = "code", nullable = false, length = 512)
    val code: String,
    @Column(name = "interval", nullable = false, length = 8)
    val interval: String,
    @Column(name = "rule_dsl", nullable = false, columnDefinition = "TEXT")
    val ruleDsl: String,
    @Column(name = "range_from", nullable = false)
    val rangeFrom: Instant,
    @Column(name = "range_to", nullable = false)
    val rangeTo: Instant,
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",
    @Column(name = "started_at")
    var startedAt: Instant? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "total_return", precision = 15, scale = 4)
    var totalReturn: BigDecimal? = null,
    @Column(name = "win_rate", precision = 7, scale = 4)
    var winRate: BigDecimal? = null,
    @Column(name = "mdd", precision = 7, scale = 4)
    var mdd: BigDecimal? = null,
    @Column(name = "sharpe", precision = 7, scale = 4)
    var sharpe: BigDecimal? = null,
    @Column(name = "sortino", precision = 7, scale = 4)
    var sortino: BigDecimal? = null,
    @Column(name = "trade_count")
    var tradeCount: Int? = null,
    @Column(name = "position_count")
    var positionCount: Int? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "JSONB")
    var detailJson: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
