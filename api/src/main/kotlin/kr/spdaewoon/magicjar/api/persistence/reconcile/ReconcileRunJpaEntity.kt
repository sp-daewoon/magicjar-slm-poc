package kr.spdaewoon.magicjar.api.persistence.reconcile

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * reconcile_run JPA 엔티티 — Flyway V25 정합 (ADR 0041·0070 §2-3 + §3-1).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건 — Spring Data default fun 함정 회피.
 *
 * 컬럼 매핑:
 * - id BIGSERIAL PK
 * - user_id BIGINT NOT NULL (multi-tenant 격리 — ADR 0010)
 * - started_at TIMESTAMPTZ NOT NULL DEFAULT now()
 * - finished_at TIMESTAMPTZ (RUNNING 단계는 null)
 * - drift_count INT NOT NULL DEFAULT 0
 * - status VARCHAR(16) NOT NULL — RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR
 * - last_error TEXT (status=ERROR 일 때만)
 * - trigger_source VARCHAR(16) NOT NULL — CRON·MANUAL·API
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Entity
@Table(name = "reconcile_run")
class ReconcileRunJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "finished_at")
    val finishedAt: Instant? = null,
    @Column(name = "drift_count", nullable = false)
    val driftCount: Int = 0,
    @Column(name = "status", nullable = false, length = 16)
    val status: String,
    @Column(name = "last_error", columnDefinition = "TEXT")
    val lastError: String? = null,
    @Column(name = "trigger_source", nullable = false, length = 16)
    val triggerSource: String,
) {
    protected constructor() : this(
        userId = 0L,
        startedAt = Instant.now(),
        status = "RUNNING",
        triggerSource = "CRON",
    )
}
