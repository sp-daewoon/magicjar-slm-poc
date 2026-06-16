package kr.spdaewoon.magicjar.api.persistence.order

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import java.math.BigDecimal
import java.time.Instant

/**
 * order_request JPA 엔티티 — Flyway V20 정합 (ADR 0068 §15-2 LOCK).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건. no-arg constructor kotlin-jpa 플러그인 의존.
 * @Version: optimistic lock (consent toggle race §15-5 정합).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Entity
@Table(name = "order_request")
class OrderRequestJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "signal_id", nullable = false, updatable = false)
    val signalId: Long,
    @Column(name = "strategy_id", nullable = false, updatable = false)
    val strategyId: Long = 0L,
    @Column(name = "user_id", nullable = false, updatable = false, length = 40)
    val userId: String,
    @Column(name = "code", nullable = false, updatable = false, length = 8)
    val code: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, updatable = false, length = 4)
    val side: OrderSide,
    @Column(name = "qty", nullable = false, updatable = false, precision = 15, scale = 4)
    val qty: BigDecimal,
    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, updatable = false, length = 10)
    val priceType: OrderPriceType,
    @Column(name = "limit_price", precision = 19, scale = 4)
    val limitPrice: BigDecimal? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.REQUESTED,
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 4)
    val mode: TradingMode,
    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 64)
    val idempotencyKey: String,
    @Column(name = "kis_order_no", length = 20)
    var kisOrderNo: String? = null,
    @Column(name = "kis_fwdg_orgno", length = 20)
    var kisFwdgOrgno: String? = null,
    @Column(name = "reject_reason", length = 255)
    var rejectReason: String? = null,
    @Column(name = "requested_at", nullable = false, updatable = false)
    val requestedAt: Instant,
    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    val source: OrderSource = OrderSource.AUTO,
    // ADR 0115 §2-P2-⑥ 키 사슬 무결성 — 발사 출처 enum + 유발 근거 실키 (Flyway V80, nullable backward compat)
    @Enumerated(EnumType.STRING)
    @Column(name = "order_trigger", updatable = false, length = 24)
    val orderTrigger: OrderTrigger? = null,
    @Column(name = "trigger_ref_id", updatable = false)
    val triggerRefId: Long? = null,
)
