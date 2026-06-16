package kr.spdaewoon.magicjar.consumer.persistence

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
 * order_request JPA 엔티티 — consumer 모듈 자체 사본 (api 모듈과 코드 중복 OK — 모듈 분리 정합).
 *
 * Flyway V20 정합 (ADR 0068 §15-1·§15-2 LOCK · 동일 테이블 `order_request`).
 *
 * ### Phase 2 wiring 결함 (backlog §61) 정합
 * api 모듈에 [kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaEntity] 동일 entity 존재하나
 * consumer는 api 모듈 미의존 → consumer JVM에서는 본 사본 entity로 합류.
 * ConsumerCandle·ConsumerInstrument·KillSwitch 패턴 동일.
 *
 * ### 헌법 §6 (ADR 0051): body 가진 fun 0건. no-arg constructor kotlin-jpa 플러그인 의존.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Entity
@Table(name = "order_request")
class ConsumerOrderRequestJpaEntity(
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
    // hotfix 2026-05-20 — T-MAN Phase 1 PR #510 회귀 (api entity 합류 OK, consumer 사본 누락).
    // Flyway V37 source 컬럼 NOT NULL DEFAULT 'AUTO' — 매핑 누락 시 DB DEFAULT fallback → MANUAL 주문도 AUTO 저장 결함.
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    val source: OrderSource = OrderSource.AUTO,
    // hotfix 2026-06-11 — D2 P2-⑥ 회귀: api entity(OrderRequestJpaEntity)에는 V80 두 컬럼이 합류돼 있으나
    // consumer 사본에 누락 → OrderRouterKafkaListener(consumer 거주)가 INSERT 하는 모든 주문의 order_trigger·
    // trigger_ref_id 가 영구 NULL(라이브 REAL 286·287 evidence). resolveOrderTrigger/resolveTriggerRefId 가
    // 도메인 차원에서 키를 산정해도 toEntity 매핑·entity 컬럼 부재로 DB 영속이 소실됐다. api entity 컬럼 정의 정합.
    @Enumerated(EnumType.STRING)
    @Column(name = "order_trigger", updatable = false, length = 24)
    val orderTrigger: OrderTrigger? = null,
    @Column(name = "trigger_ref_id", updatable = false)
    val triggerRefId: Long? = null,
) {
    protected constructor() : this(
        signalId = 0L,
        userId = "",
        code = "",
        side = OrderSide.BUY,
        qty = BigDecimal.ZERO,
        priceType = OrderPriceType.MARKET,
        mode = TradingMode.MOCK,
        idempotencyKey = "",
        requestedAt = Instant.EPOCH,
    )
}
