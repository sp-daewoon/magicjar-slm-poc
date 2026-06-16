package kr.spdaewoon.magicjar.api.persistence.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 전략 평가 metric JPA 엔티티 — Flyway V27 신규 테이블.
 *
 * ADR 0047 §2-6 정합 — 마지막 N=100건 ring buffer.
 * strategy_eval_metric 테이블: 평가 결과 + 소요 시간 + 오류 코드.
 */
@Entity
@Table(name = "strategy_eval_metric")
class StrategyEvalMetricJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "evaluated_at", nullable = false)
    val evaluatedAt: Instant = Instant.now(),
    @Column(name = "eval_time_ms", nullable = false)
    val evalTimeMs: Int,
    @Column(name = "cpu_time_ms")
    val cpuTimeMs: Int?,
    /**
     * 평가 결과 코드 — OK·TIMEOUT·CPU_EXCEEDED·UNKNOWN_SYMBOL·SYNTAX_ERROR.
     */
    @Column(name = "outcome", nullable = false, length = 32)
    val outcome: String,
    @Column(name = "error_msg", columnDefinition = "TEXT")
    val errorMsg: String?,
) {
    protected constructor() : this(
        strategyId = 0,
        evalTimeMs = 0,
        cpuTimeMs = null,
        outcome = "OK",
        errorMsg = null,
    )
}
