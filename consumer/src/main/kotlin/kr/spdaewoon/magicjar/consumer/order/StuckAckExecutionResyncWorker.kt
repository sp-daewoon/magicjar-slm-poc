package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * ACK 정체 주문 timeout 재조회 — ADR 0115 §P2-4 (R2 배선).
 *
 * 2026-06-08 incident 주문 144·176 — WS 체결통보 누락으로 주문이 ACK/PARTIALLY_FILLED 상태에 영구 정체했다.
 * [KisExecutionBackfillService](#815)는 **재연결 트리거가 있을 때만** 복구하므로, 연결이 유지된 채 단순 통보만
 * 누락되면 정체가 지속된다. 본 worker는 [OrderReconciliationWorker](PENDING_RECONCILE 전용)와 **상보적**으로,
 * **ACK/PARTIALLY_FILLED 상태가 timeout([ackStaleThresholdSeconds]) 넘게 정체한 주문**을 발견하면 해당 사용자·모드의
 * [KisExecutionBackfillService.syncUserTodayExecutions]를 발사해 KIS 당일체결 대조로 FILLED/취소를 정정한다.
 *
 * ## 신규 로직 최소 (ADR 0115 §P2-4 — 기존 컴포넌트 재사용)
 * - lookup: 기존 [OrderRequestRepository.findByStatusAndRequestedAtBefore](status·cutoff) 재사용(신규 query 0).
 * - 재조회: 기존 [KisExecutionBackfillService] sync 재사용(신규 KIS 호출 로직 0).
 * - 동일 (userId, mode) 묶음당 1회만 sync(중복 KIS 조회 회피 — rate limit 보호).
 *
 * ## cron 2분 (fixedDelay)
 * 직전 호출 완료 시점부터 간격 — KIS rate limit 보호([OrderReconciliationWorker] 1분 정합 + grace). 정체 0건이면 즉시 종료.
 *
 * ## ENV
 * `magicjar.order.ack-resync.enabled`(default true) · `magicjar.order.ack-resync.stale-threshold-seconds`(default 180).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4)
 */
@Component
class StuckAckExecutionResyncWorker(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val executionBackfillServiceProvider: ObjectProvider<KisExecutionBackfillService>,
    @Value("\${magicjar.order.ack-resync.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.order.ack-resync.stale-threshold-seconds:180}")
    private val ackStaleThresholdSeconds: Long = 180,
) {
    private val log = LoggerFactory.getLogger(StuckAckExecutionResyncWorker::class.java)

    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    fun resyncTick() {
        if (!enabled) return
        val repo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val backfill = executionBackfillServiceProvider.getIfAvailable()
        if (backfill == null) {
            log.debug("StuckAckExecutionResyncWorker — KisExecutionBackfillService 미합류 skip")
            return
        }

        val cutoff = Instant.now().minus(Duration.ofSeconds(ackStaleThresholdSeconds.coerceAtLeast(0)))
        val stuck =
            STUCK_STATUSES.flatMap { status ->
                runCatching { repo.findByStatusAndRequestedAtBefore(status, cutoff, BATCH_LIMIT) }
                    .onFailure { e -> log.warn("ACK 정체 lookup 실패: status={} err={}", status, e.message) }
                    .getOrDefault(emptyList())
            }
        if (stuck.isEmpty()) return

        // 동일 (userId, mode) 묶음당 1회만 KIS 당일체결 재조회(중복 호출 회피).
        val targets = stuck.map { it.userId to accountTypeOf(it) }.toSet()
        log.warn(
            "StuckAckExecutionResyncWorker — ACK 정체 {}건 / {}계정 재조회 발사(WS 통보 누락 추정): cutoffSec={}",
            stuck.size,
            targets.size,
            ackStaleThresholdSeconds,
        )
        for ((userId, accountType) in targets) {
            runCatching { backfill.syncUserTodayExecutions(userId, accountType) }
                .onFailure { e ->
                    log.warn(
                        "ACK 정체 재조회 실패(다음 tick 재시도): userId={} accountType={} err={}",
                        maskUserId(userId),
                        accountType,
                        e.message,
                    )
                }
        }
    }

    private fun accountTypeOf(order: OrderRequest): AccountType = if (order.mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private companion object {
        /** 정체 추적 대상 — KIS가 받았으나(ACK) 종결 통보(FILLED/CANCELLED)가 누락될 수 있는 상태. */
        val STUCK_STATUSES = listOf(OrderStatus.ACK, OrderStatus.PARTIALLY_FILLED)
        const val BATCH_LIMIT = 50
    }
}
