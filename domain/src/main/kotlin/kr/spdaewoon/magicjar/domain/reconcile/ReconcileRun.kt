package kr.spdaewoon.magicjar.domain.reconcile

import java.time.Instant

/**
 * Reconcile 실행 도메인 (ADR 0041·0070 §2-2 + §3-1 V25 컬럼 매핑).
 *
 * Spring·JPA 의존 0 — 순수 도메인.
 *
 * 매핑 (Phase 2 backend-core JPA reconcile_run 테이블):
 * - id BIGSERIAL PK
 * - user_id BIGINT NOT NULL (multi-tenant 격리)
 * - started_at TIMESTAMPTZ NOT NULL DEFAULT now()
 * - finished_at TIMESTAMPTZ (RUNNING 단계는 null)
 * - drift_count INT NOT NULL DEFAULT 0
 * - status VARCHAR(16) NOT NULL — [ReconcileStatus]
 * - last_error TEXT (status=ERROR 일 때만)
 * - trigger_source VARCHAR(16) NOT NULL — [ReconcileTriggerSource]
 *
 * 본 도메인 단계는 도메인 모델만 LOCK — JPA Entity·Repository 어댑터는 Phase 2 backend-core.
 */
data class ReconcileRun(
    val id: Long? = null,
    val userId: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val driftCount: Int = 0,
    val status: ReconcileStatus = ReconcileStatus.RUNNING,
    val lastError: String? = null,
    val triggerSource: ReconcileTriggerSource = ReconcileTriggerSource.CRON,
)
