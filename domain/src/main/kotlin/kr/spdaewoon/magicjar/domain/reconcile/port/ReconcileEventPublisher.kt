package kr.spdaewoon.magicjar.domain.reconcile.port

import kr.spdaewoon.magicjar.domain.reconcile.HoldingDiff
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun

/**
 * Reconcile 이벤트 Kafka publish 포트 (ADR 0070 §4-1 신규 토픽 매트릭스).
 *
 * 구현: consumer `ReconcileEventKafkaPublisher` — KafkaTemplate 의존.
 *
 * publish 토픽 3건:
 * - `reconcile.run.v1` (retention 7d) — RUNNING·RECONCILED·... 모든 status 통보 (audit + STOMP fanout)
 * - `reconcile.drift.v1` (retention 30d) — drift 검출 시 종목별 1건씩 publish (자본 사고 직결)
 * - `killswitch.engaged.v1` (retention 30d) — KillSwitch auto-ON 시 publish
 *
 * partition key = userId — 사용자별 ordering 보장.
 */
interface ReconcileEventPublisher {
    /** run lifecycle 통보 (RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR). */
    fun publishRun(run: ReconcileRun)

    /** drift 검출 시 종목별 1건씩 publish (자본 사고 직결). */
    fun publishDrift(
        userId: String,
        runId: Long?,
        diff: HoldingDiff,
    )

    /** KillSwitch auto-ON publish (drift 검출 직후). */
    fun publishKillSwitchEngaged(
        userId: String,
        reason: String,
        triggeredBy: String,
    )
}
