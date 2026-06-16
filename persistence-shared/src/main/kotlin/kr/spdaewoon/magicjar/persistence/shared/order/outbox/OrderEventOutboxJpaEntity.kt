package kr.spdaewoon.magicjar.persistence.shared.order.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * order_event_outbox JPA 엔티티 — Flyway V40 정합 (ADR 0084 P1-1 + spec 2026-05-18 §2-5).
 *
 * ADR 0051 정합:
 *   - no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존
 *   - 헌법 §6: body 가진 fun 0건 — Spring Data default fun 함정 회피
 *
 * 컬럼 매핑 (Flyway V40 본문 단일 출처):
 *   - id BIGSERIAL PK
 *   - event_id UUID NOT NULL UNIQUE (멱등 키)
 *   - event_type VARCHAR(64) NOT NULL — ORDER_INTENT·ORDER_REQUESTED·ORDER_REJECTED·ORDER_FILLED
 *   - topic VARCHAR(96) NOT NULL — order.intent.v1·order.requested.v1·order.rejected.v1·order.filled.v1
 *   - partition_key VARCHAR(64) — partition key (userId)
 *   - payload_json JSONB NOT NULL
 *   - status VARCHAR(16) NOT NULL — PENDING·PUBLISHED·FAILED
 *   - created_at TIMESTAMPTZ NOT NULL DEFAULT now()
 *   - published_at TIMESTAMPTZ
 *   - attempt_count INT NOT NULL DEFAULT 0
 *   - last_error TEXT
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
@Entity
@Table(name = "order_event_outbox")
class OrderEventOutboxJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "event_id", nullable = false, columnDefinition = "uuid")
    val eventId: UUID,
    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,
    @Column(name = "topic", nullable = false, length = 96)
    val topic: String,
    @Column(name = "partition_key", length = 64)
    val partitionKey: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    val payloadJson: String,
    @Column(name = "status", nullable = false, length = 16)
    var status: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,
) {
    protected constructor() : this(
        eventId = UUID.randomUUID(),
        eventType = "ORDER_INTENT",
        topic = KafkaTopics.Order.INTENT,
        payloadJson = "{}",
        status = "PENDING",
    )
}
