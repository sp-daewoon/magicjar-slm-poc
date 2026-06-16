package kr.spdaewoon.magicjar.persistence.shared.event

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * system_event JPA 엔티티 — Flyway V44 정합 (spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6.1).
 *
 * ### ADR 0051 정합
 * - no-arg constructor 필요 — kotlin-jpa 플러그인 의존 (api·consumer ApplicationContext 양쪽 wiring)
 * - 헌법 §6: body 가진 fun 0건 — Spring Data default fun 함정 회피 (helper는 caller adapter private fun)
 *
 * ### detail_json jsonb cast
 * `@JdbcTypeCode(SqlTypes.JSON)` 의무 — Hibernate 6은 Kotlin String 을 `character varying` 으로 전달 →
 * PostgreSQL JSONB 컬럼과 타입 충돌 (v3.6.18 dogfooding evidence — StompEventJpaEntity 동일 hotfix 정합).
 * caller (SystemEventLoggerAdapter)는 emit site 에서 valid JSON 문자열 보장 의무.
 *
 * ### 컬럼 매핑 (V44 본문 단일 출처)
 * - id BIGSERIAL PK
 * - category VARCHAR(20) NOT NULL — SystemEventCategory enum name
 * - severity VARCHAR(10) NOT NULL — SystemEventSeverity enum name (INFO·WARN·ERROR·CRITICAL)
 * - actor VARCHAR(60) NOT NULL — 발사 component (예: "OrderReconciliationWorker")
 * - action VARCHAR(80) NOT NULL — 이벤트 코드 (예: "RECONCILE_FALLBACK_FIRED")
 * - user_id VARCHAR(40) — null = system-wide broadcast
 * - correlation_id VARCHAR(64) — 동일 사건 묶기 (WS disconnect→reconnect 같은 case)
 * - detail_json JSONB NOT NULL ← `@JdbcTypeCode(SqlTypes.JSON)` 의무
 * - occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
 * - resolved_at TIMESTAMPTZ — P2 UI 마킹 (resolve 버튼)
 * - viewed_at TIMESTAMPTZ — P2 UI 마킹 (read 처리)
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Entity
@Table(name = "system_event")
class SystemEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "category", nullable = false, updatable = false, length = 20)
    val category: String,
    @Column(name = "severity", nullable = false, updatable = false, length = 10)
    val severity: String,
    @Column(name = "actor", nullable = false, updatable = false, length = 60)
    val actor: String,
    @Column(name = "action", nullable = false, updatable = false, length = 80)
    val action: String,
    @Column(name = "user_id", updatable = false, length = 40)
    val userId: String? = null,
    @Column(name = "correlation_id", updatable = false, length = 64)
    val correlationId: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", nullable = false, columnDefinition = "jsonb")
    val detailJson: String,
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
    @Column(name = "viewed_at")
    var viewedAt: Instant? = null,
) {
    protected constructor() : this(
        category = "",
        severity = "",
        actor = "",
        action = "",
        detailJson = "{}",
        occurredAt = Instant.EPOCH,
    )

    fun toDomain(): SystemEvent =
        SystemEvent(
            category = runCatching { SystemEventCategory.valueOf(category) }.getOrDefault(SystemEventCategory.OTHER),
            severity = runCatching { SystemEventSeverity.valueOf(severity) }.getOrDefault(SystemEventSeverity.INFO),
            actor = actor,
            action = action,
            userId = userId,
            correlationId = correlationId,
            detailJson = detailJson,
            occurredAt = occurredAt,
        )

    companion object {
        fun fromDomain(event: SystemEvent): SystemEventJpaEntity =
            SystemEventJpaEntity(
                category = event.category.name,
                severity = event.severity.name,
                actor = event.actor,
                action = event.action,
                userId = event.userId,
                correlationId = event.correlationId,
                detailJson = event.detailJson,
                occurredAt = event.occurredAt,
            )
    }
}
