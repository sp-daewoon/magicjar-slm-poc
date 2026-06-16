package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ManualReconcileService 단위 테스트 (ADR 0070 §6-1 idempotency + §5 분산락).
 *
 * 3 케이스:
 *  (a) lastrun 캐시 hit → Cached 반환 (실제 KIS 호출 X)
 *  (b) lock 미취득 → LockBusy 반환 (cron 또는 다른 manual 동시 실행)
 *  (c) 신규 실행 — lock 취득 + reconcileUser 호출 → Completed
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class ManualReconcileServiceTest {
    private val balanceReconcileService = mockk<BalanceReconcileService>()
    private val lockAdapter = mockk<ReconcileLockAdapter>(relaxed = true)
    private val worker = mockk<StrategyPositionReconcileWorker>(relaxed = true)
    private val workerProvider =
        mockk<org.springframework.beans.factory.ObjectProvider<StrategyPositionReconcileWorker>> {
            every { getIfAvailable() } returns worker
        }
    private val service = ManualReconcileService(balanceReconcileService, lockAdapter, workerProvider, "MOCK")

    @Test
    fun `(a) lastrun 캐시 hit — Cached 반환 + 실제 KIS 호출 안 함`() {
        every { lockAdapter.lookupLastRun("U1") } returns "555"

        val outcome = service.trigger("U1")

        assertThat(outcome).isInstanceOf(ManualReconcileOutcome.Cached::class.java)
        assertThat((outcome as ManualReconcileOutcome.Cached).lastRunId).isEqualTo(555L)
        verify(exactly = 0) { balanceReconcileService.reconcileUser(any(), any()) }
        verify(exactly = 0) { lockAdapter.acquireLock(any()) }
    }

    @Test
    fun `(b) lock 미취득 — LockBusy 반환 (cron 동시 실행 가정)`() {
        every { lockAdapter.lookupLastRun("U1") } returns null
        every { lockAdapter.acquireLock("U1") } returns false

        val outcome = service.trigger("U1")

        assertThat(outcome).isEqualTo(ManualReconcileOutcome.LockBusy)
        verify(exactly = 0) { balanceReconcileService.reconcileUser(any(), any()) }
    }

    @Test
    fun `(c) 신규 실행 — lock 취득 + reconcileUser 호출 → Completed`() {
        every { lockAdapter.lookupLastRun("U1") } returns null
        every { lockAdapter.acquireLock("U1") } returns true
        val run =
            ReconcileRun(
                id = 777L,
                userId = "U1",
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                driftCount = 0,
                status = ReconcileStatus.RECONCILED,
                triggerSource = ReconcileTriggerSource.MANUAL,
            )
        every { balanceReconcileService.reconcileUser("U1", ReconcileTriggerSource.MANUAL) } returns run

        val outcome = service.trigger("U1")

        assertThat(outcome).isInstanceOf(ManualReconcileOutcome.Completed::class.java)
        assertThat((outcome as ManualReconcileOutcome.Completed).run.id).isEqualTo(777L)
        verify { lockAdapter.cacheLastRun("U1", 777L) }
        verify { lockAdapter.releaseLock("U1") }
        // 2026-06-10 drill 결함 회귀 — manual 경로도 CRON과 동일하게 strategy_position 정합 동반 (ADR 0115 §P1-1)
        verify { worker.reconcileForUser("U1", "MOCK") }
    }
}
