package kr.spdaewoon.magicjar.persistence.shared.notification.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * notification_outbox JPA 엔티티 — Flyway V27 정합 (ADR 0044 + ADR 0070 §3-3).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건 — Spring Data default fun 함정 회피.
 *
 * 컬럼 매핑:
 * - id BIGSERIAL PK
 * - event_id UUID NOT NULL (UNIQUE — dedup 키)
 * - channel VARCHAR(16) NOT NULL — SLACK·(FUTURE: EMAIL·SMS·PUSH·DISCORD)
 * - priority VARCHAR(4) NOT NULL — P0·P1·P2
 * - payload JSONB NOT NULL
 * - status VARCHAR(16) NOT NULL — PENDING·SENT·FAILED
 * - created_at TIMESTAMPTZ NOT NULL DEFAULT now()
 * - sent_at TIMESTAMPTZ
 * - retry_count INT NOT NULL DEFAULT 0
 * - last_error TEXT
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Entity
@Table(name = "notification_outbox")
class NotificationOutboxJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "event_id", nullable = false, columnDefinition = "uuid")
    val eventId: UUID,
    @Column(name = "channel", nullable = false, length = 16)
    val channel: String,
    @Column(name = "priority", nullable = false, length = 4)
    val priority: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,
    @Column(name = "status", nullable = false, length = 16)
    var status: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "sent_at")
    var sentAt: Instant? = null,
    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,
    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,
) {
    protected constructor() : this(
        eventId = UUID.randomUUID(),
        channel = "SLACK",
        priority = "P2",
        payload = "{}",
        status = "PENDING",
    )
}
