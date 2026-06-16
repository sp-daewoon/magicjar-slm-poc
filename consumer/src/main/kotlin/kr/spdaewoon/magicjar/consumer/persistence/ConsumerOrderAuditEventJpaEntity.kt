package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * order_audit_event JPA 엔티티 — consumer 모듈 자체 사본 (api 모듈과 코드 중복 OK — 모듈 분리 정합).
 *
 * Flyway V22 정합 (ADR 0068 §15-1·§15-2 LOCK · 동일 테이블 `order_audit_event`).
 *
 * ### Phase 2 wiring 결함 (backlog §61) 정합
 * api 모듈에 [kr.spdaewoon.magicjar.api.persistence.order.OrderAuditEventJpaEntity] 동일 entity 존재하나
 * consumer는 api 모듈 미의존 → consumer JVM에서는 본 사본 entity로 합류.
 *
 * order_id NULL 허용: IDEMPOTENCY_SKIPPED 시 orderId 없이 INSERT (§14-2 step 2 정합).
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Entity
@Table(name = "order_audit_event")
class ConsumerOrderAuditEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "order_id")
    val orderId: Long? = null,
    @Column(name = "user_id", nullable = false, updatable = false, length = 40)
    val userId: String,
    @Column(name = "action", nullable = false, updatable = false, length = 40)
    val action: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    val detailJson: String? = null,
    @Column(name = "ts", nullable = false, updatable = false)
    val ts: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = "",
        action = "",
    )
}
