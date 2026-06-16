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
 * audit_trail JPA 엔티티 (consumer 모듈 — ADR 0015).
 * Bootstrap 시작·종료·실패 감사 로그.
 */
@Entity
@Table(name = "audit_trail")
class ConsumerAuditTrailJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "actor", length = 40, nullable = false)
    val actor: String,
    @Column(name = "action", length = 60, nullable = false)
    val action: String,
    @Column(name = "entity_type", length = 40, nullable = false)
    val entityType: String,
    @Column(name = "entity_id", length = 40, nullable = false)
    val entityId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "JSONB")
    val detailJson: String? = null,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now(),
) {
    protected constructor() : this(actor = "", action = "", entityType = "", entityId = "")
}
