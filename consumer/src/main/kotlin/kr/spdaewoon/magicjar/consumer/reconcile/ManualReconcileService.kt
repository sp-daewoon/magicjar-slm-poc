package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 사용자 수동 reconcile trigger 서비스 (ADR 0070 §6-1 manual trigger).
 *
 * Phase 2 backend-core가 REST endpoint `POST /api/risk/reconcile/{userId}`에서 본 service 호출 예정.
 * Phase 1은 service만 LOCK — REST controller·DTO·Swagger는 Phase 2 책임.
 *
 * ### idempotency (ADR 0070 §6-1 LOCK)
 * Redis `reconcile:lastrun:{userId}` 5분 TTL — 5분 내 재호출 시 직전 result(runId)만 반환 권장.
 * 본 service는 lastrun 캐시 hit 시 신규 cycle skip + 직전 runId 반환만 — 실제 KIS 호출은 안 함.
 *
 * ### 분산락 (ADR 0070 §5)
 * Manual trigger도 cron과 동일 `reconcile:lock:{userId}` 5분 분산락 적용 — 동시 다중 manual + cron 충돌 방지.
 * lock 미취득 시 직전 lastrun 캐시 또는 LOCK_BUSY status로 즉시 반환.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Service
class ManualReconcileService(
    private val balanceReconcileService: BalanceReconcileService,
    private val reconcileLockAdapter: ReconcileLockAdapter,
    private val strategyPositionReconcileWorkerProvider: ObjectProvider<StrategyPositionReconcileWorker>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) {
    private val log = LoggerFactory.getLogger(ManualReconcileService::class.java)

    /**
     * 사용자 수동 reconcile trigger.
     *
     * @return ReconcileRun (idempotency cache hit 시 직전 runId, 그 외 신규 실행 결과)
     */
    fun trigger(userId: String): ManualReconcileOutcome {
        // 1. lastrun 캐시 hit → 직전 결과 반환 (실제 KIS 호출 X)
        val lastRunId = reconcileLockAdapter.lookupLastRun(userId)
        if (lastRunId != null) {
            log.info(
                "ManualReconcileService — idempotency cache hit: userId={} lastRunId={}",
                maskUserId(userId),
                lastRunId,
            )
            return ManualReconcileOutcome.Cached(lastRunId.toLongOrNull())
        }

        // 2. 분산락 acquire — manual은 fail-close (lock 미취득 시 즉시 LOCK_BUSY)
        if (!reconcileLockAdapter.acquireLock(userId)) {
            log.warn("ManualReconcileService — lock 미취득: userId={}", maskUserId(userId))
            return ManualReconcileOutcome.LockBusy
        }

        return try {
            val result = balanceReconcileService.reconcileUser(userId, ReconcileTriggerSource.MANUAL)
            result.id?.let { reconcileLockAdapter.cacheLastRun(userId, it) }
            // KIS position 정합 직후 strategy_position drift 정합 — CRON 경로(BalanceReconcileJob)와 동일 흐름
            // (ADR 0115 §P1-1 amend). 2026-06-10 drill evidence: manual 경로만 worker 우회 → drift 잔존.
            reconcileStrategyPositions(userId)
            ManualReconcileOutcome.Completed(result)
        } finally {
            reconcileLockAdapter.releaseLock(userId)
        }
    }

    /** strategy_position drift 정합 — ObjectProvider graceful (BalanceReconcileJob.reconcileStrategyPositions 정합). */
    private fun reconcileStrategyPositions(userId: String) {
        val worker = strategyPositionReconcileWorkerProvider.getIfAvailable() ?: return
        val modeName = RuntimeModeParser.accountTypeOrMock(magicjarMode).name
        runCatching { worker.reconcileForUser(userId, modeName) }
            .onFailure { e ->
                log.warn(
                    "ManualReconcileService — strategy_position 정합 실패(본체 무영향): userId={} err={}",
                    maskUserId(userId),
                    e.message,
                )
            }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}

/**
 * Manual trigger 결과 sealed (Phase 2 REST controller가 HTTP status로 매핑).
 *
 * - [Cached]: 5분 내 직전 실행 결과 재사용 (HTTP 200 + 직전 runId)
 * - [Completed]: 신규 실행 완료 (HTTP 202 + run 본문)
 * - [LockBusy]: 다른 인스턴스 또는 cron 동시 실행 중 (HTTP 409)
 */
sealed class ManualReconcileOutcome {
    data class Cached(
        val lastRunId: Long?,
    ) : ManualReconcileOutcome()

    data class Completed(
        val run: ReconcileRun,
    ) : ManualReconcileOutcome()

    object LockBusy : ManualReconcileOutcome()
}
