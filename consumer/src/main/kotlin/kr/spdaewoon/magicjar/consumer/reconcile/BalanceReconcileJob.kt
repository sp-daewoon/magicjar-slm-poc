package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Reconcile cron 워커 (ADR 0070 §2-9 + §5 분산락).
 *
 * ### cron 매트릭스 (ADR 0070 §2-9 LOCK)
 * | 시점 | cron expression | 사유 |
 * |---|---|---|
 * | 시장 OPEN | `0 *\/5 9-15 * * MON-FRI` (KST) | 매매 활성 — drift 빠른 감지 |
 * | 시장 CLOSED | `0 0 *\/1 * * *` (1시간 주기) | 야간·주말 catch-up |
 *
 * 본 PR은 시장 OPEN cron 단일 LOCK — CLOSED 1시간 catch-up은 backlog §48 (MarketSessionFsm 통합 시 보강).
 *
 * ### 분산락 패턴 (ADR 0038 KIS OAuth 분산락 정합)
 * Redis `reconcile:lock:{userId}` SETNX 5분 — 다중 인스턴스 동시 실행 방지.
 *
 * ### Phase 2 graceful fallback
 * `ConsumerUserKisCredentialJpaRepository`는 baseline 이미 존재 — 활성 user 조회는 Phase 1 단독 가능.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class BalanceReconcileJob(
    private val balanceReconcileService: BalanceReconcileService,
    private val reconcileLockAdapter: ReconcileLockAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    /**
     * ADR 0115 §P1-1 amend (2026-06-10) — KIS position 정합 직후 strategy_position 부족 drift 정합.
     * ObjectProvider graceful — 어댑터 미합류 시 strategy_position 정합 skip(BalanceReconcile 본체는 정상).
     */
    private val strategyPositionReconcileWorkerProvider: ObjectProvider<StrategyPositionReconcileWorker>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
) {
    private val log = LoggerFactory.getLogger(BalanceReconcileJob::class.java)

    /**
     * 시장 OPEN 5분 cron — KST 09:00~15:55 MON-FRI.
     *
     * cron: 매 5분 30초 (예: 09:00:30, 09:05:30, ..., 15:55:30).
     * 2026-06-15 REAL 1분 신호가 정각 0초에 KIS USER 경로를 쓰므로, reconcile BACKFILL은 30초로 분산한다.
     */
    @Scheduled(cron = "\${magicjar.reconcile.market-open-cron:30 */5 9-15 * * MON-FRI}", zone = "Asia/Seoul")
    fun runMarketOpenCron() {
        log.info("BalanceReconcileJob — 시장 OPEN cron fires")
        runForAllActiveUsers(ReconcileTriggerSource.CRON)
    }

    /**
     * 활성 user 전체 reconcile 실행 (직렬 — Rate Limit 안전).
     *
     * @param triggerSource CRON · MANUAL · API
     */
    internal fun runForAllActiveUsers(triggerSource: ReconcileTriggerSource) {
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val activeCreds = credentialRepository.findAllByAccountType(accountType.name)
        if (activeCreds.isEmpty()) {
            log.info("BalanceReconcileJob — 활성 사용자 0명 idle (accountType={})", accountType.name)
            return
        }

        log.info("BalanceReconcileJob — 활성 user {}명 처리 시작 (accountType={})", activeCreds.size, accountType.name)

        activeCreds.forEach { cred ->
            val userId = cred.id.userId
            if (!reconcileLockAdapter.acquireLock(userId)) {
                log.info("BalanceReconcileJob — lock 미취득 (다른 인스턴스 처리 중): userId={}", maskUserId(userId))
                return@forEach
            }
            try {
                val result = balanceReconcileService.reconcileUser(userId, triggerSource)
                result.id?.let { reconcileLockAdapter.cacheLastRun(userId, it) }
                // KIS position 정합 직후 strategy_position 부족 drift 정합 (ADR 0115 §P1-1 amend).
                // position 테이블이 KIS 잔고로 최신화된 직후라 strategy_position 정렬의 권위 기준이 신선.
                reconcileStrategyPositions(userId, accountType.name)
            } catch (e: Exception) {
                log.error("BalanceReconcileJob — user 처리 실패: userId={} err={}", maskUserId(userId), e.message, e)
            } finally {
                reconcileLockAdapter.releaseLock(userId)
            }
        }

        log.info("BalanceReconcileJob — 완료")
    }

    /**
     * strategy_position 부족 drift 정합 — ObjectProvider graceful (어댑터 미합류 시 skip).
     * worker 자체가 종목별 try-catch 이므로 본 wrap 은 worker bean 부재·일괄 호출 실패만 가드.
     */
    private fun reconcileStrategyPositions(
        userId: String,
        modeName: String,
    ) {
        val worker = strategyPositionReconcileWorkerProvider.getIfAvailable() ?: return
        runCatching { worker.reconcileForUser(userId, modeName) }
            .onFailure { e ->
                log.warn(
                    "BalanceReconcileJob — strategy_position 정합 실패(본체 무영향): userId={} err={}",
                    maskUserId(userId),
                    e.message,
                )
            }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
