package kr.spdaewoon.magicjar.persistence.shared.stomp

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
 * stomp_event JPA 엔티티 — Flyway V41 정합 (ADR 0084 P1-2).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 *
 * ### payload_json jsonb cast (2026-05-19 hotfix)
 * - `@JdbcTypeCode(SqlTypes.JSON)` 누락 시 Hibernate 6은 Kotlin `String`을 `character varying`으로
 *   전달 → PostgreSQL JSONB 컬럼과 타입 충돌 (v3.6.18 dogfooding evidence —
 *   `ERROR: column "payload_json" is of type jsonb but expression is of type character varying`).
 * - 동일 패턴 정합: [kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaEntity]
 *   · [kr.spdaewoon.magicjar.api.persistence.order.OrderAuditEventJpaEntity]
 *   · [kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity] (PR #530).
 * - 직렬화는 caller([kr.spdaewoon.magicjar.api.messaging.StompEventPersister])가 ObjectMapper로 valid JSON 보장.
 *
 * ### 컬럼 매핑
 * - id BIGSERIAL PK
 * - user_id BIGINT NOT NULL
 * - channel VARCHAR(64) NOT NULL
 * - event_type VARCHAR(64) NOT NULL
 * - payload_json JSONB NOT NULL  ← `@JdbcTypeCode(SqlTypes.JSON)` 의무
 * - created_at TIMESTAMPTZ NOT NULL DEFAULT now()
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Entity
@Table(name = "stomp_event")
class StompEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "channel", nullable = false, length = 64)
    val channel: String,
    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    val payloadJson: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = 0L,
        channel = "",
        eventType = "",
        payloadJson = "{}",
    )
}
