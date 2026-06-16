package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * order_execution JPA 엔티티 — consumer 모듈 자체 사본 (api 모듈과 코드 중복 OK — 모듈 분리 정합).
 *
 * Flyway V21 정합 (ADR 0068 §15-1·§15-2 LOCK · 동일 테이블 `order_execution`).
 *
 * ### T-O Phase 1 B-2 합류 (spec `2026-05-18-to-main-track-entry-plan.md` §B-2)
 * api 모듈에 [kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaEntity] 동일 entity 존재하나
 * consumer는 api 모듈 미의존 → consumer JVM에서는 본 사본 entity로 합류.
 * `KisExecutionWsListener.handleExecution()` 의 `OrderExecutionRepository` ObjectProvider 가 graceful skip 으로 빠지지 않고
 * 본 사본을 통해 정상 영속되도록 보장한다 (PR #481 의 `ConsumerOrderRequestJpaEntity` ·
 * `ConsumerOrderAuditEventJpaEntity` 동일 패턴 third 합류).
 *
 * 분할 체결 1:N — H0STCNI0 CNTG_YN="2" 수신마다 INSERT.
 *
 * ### 헌법 §6 (ADR 0051): body 가진 fun 0건. no-arg constructor kotlin-jpa 플러그인 의존.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Entity
@Table(name = "order_execution")
class ConsumerOrderExecutionJpaEntity(
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
    @Column(name = "kis_execution_no", length = 20)
    val kisExecutionNo: String? = null,
    /**
     * ADR 0084 P0-3 — WS + REST backfill 멱등 키.
     * Flyway V35 — `order_execution.event_id VARCHAR(64) UNIQUE` partial index (NULL 허용).
     * 합성 규칙: `{kisOrderNo}:{stckCntgHour}:{cntgQty}:{price}` (KisExecutionWsListener·backfill 양 경로 동일).
     */
    @Column(name = "event_id", length = 64)
    val eventId: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        orderId = 0L,
        executedQty = BigDecimal.ZERO,
        executedPrice = BigDecimal.ZERO,
        executedAt = Instant.EPOCH,
    )
}
