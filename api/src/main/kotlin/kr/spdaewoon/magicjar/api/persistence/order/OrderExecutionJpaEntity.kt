package kr.spdaewoon.magicjar.api.persistence.order

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * order_execution JPA 엔티티 — Flyway V21 정합 (ADR 0068 §15-2 LOCK).
 *
 * 분할 체결 1:N — H0STCNI0 CNTG_YN="2" 수신마다 INSERT.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Entity
@Table(name = "order_execution")
class OrderExecutionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "order_id", nullable = false, updatable = false)
    val orderId: Long,
    @Column(name = "executed_qty", nullable = false, updatable = false, precision = 15, scale = 4)
    val executedQty: BigDecimal,
    @Column(name = "executed_price", nullable = false, updatable = false, precision = 19, scale = 4)
    val executedPrice: BigDecimal,
    @Column(name = "executed_at", nullable = false, updatable = false)
    val executedAt: Instant,
    @Column(name = "fee", nullable = false, precision = 19, scale = 4)
    val fee: BigDecimal = BigDecimal.ZERO,
    @Column(name = "tax", nullable = false, precision = 19, scale = 4)
    val tax: BigDecimal = BigDecimal.ZERO,
    @Column(name = "realized_pnl", precision = 18, scale = 2)
    val realizedPnl: BigDecimal? = null,
    @Column(name = "kis_execution_no", length = 20)
    val kisExecutionNo: String? = null,
    /**
     * ADR 0084 P0-3 — WS + REST backfill 멱등 키 (Flyway V35 uq_execution_event_id partial UNIQUE).
     * 합성 규칙: `{kisOrderNo}:{stckCntgHour}:{cntgQty}:{price}`.
     */
    @Column(name = "event_id", length = 64)
    val eventId: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
