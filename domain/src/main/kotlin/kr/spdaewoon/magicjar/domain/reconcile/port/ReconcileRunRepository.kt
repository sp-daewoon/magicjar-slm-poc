package kr.spdaewoon.magicjar.domain.reconcile.port

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun

/**
 * Reconcile 실행 영속 포트 (ADR 0070 §2-2 + §3-1 V25).
 *
 * 구현: Phase 2 backend-core `api/.../persistence/reconcile/ReconcileRunRepositoryAdapter.kt` (JPA).
 * Phase 1 consumer는 본 port abstract만 — 어댑터 부재 시 ObjectProvider graceful skip + log.warn.
 *
 * 헌법 §6 LOCK: abstract fun만 — JPA Repository default fun 함정 회피.
 */
interface ReconcileRunRepository {
    fun save(run: ReconcileRun): ReconcileRun

    fun findById(id: Long): ReconcileRun?

    fun findByUserId(
        userId: String,
        limit: Int,
        offset: Int,
    ): List<ReconcileRun>
}
