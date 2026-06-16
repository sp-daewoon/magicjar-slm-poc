package kr.spdaewoon.magicjar.batch.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * batch 모듈용 order_execution 경량 JPA 엔티티 (읽기 전용 — DailyPerformanceJob 집계용).
 *
 * order_request JOIN을 통해 user_id 접근.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 * // KIS 호출 0 — 영속 데이터 reuse (ADR 0072 §1-4 LOCK)
 */
@Entity
@Table(name = "order_execution")
class BatchOrderExecutionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    val orderRequest: BatchOrderRequestJpaEntity,
    @Column(name = "executed_qty", nullable = false, precision = 15, scale = 4)
    val executedQty: BigDecimal = BigDecimal.ZERO,
    @Column(name = "executed_price", nullable = false, precision = 19, scale = 4)
    val executedPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant,
    @Column(name = "fee", nullable = false, precision = 19, scale = 4)
    val fee: BigDecimal = BigDecimal.ZERO,
    @Column(name = "tax", nullable = false, precision = 19, scale = 4)
    val tax: BigDecimal = BigDecimal.ZERO,
    @Column(name = "realized_pnl", precision = 18, scale = 2)
    val realizedPnl: BigDecimal? = null,
)
