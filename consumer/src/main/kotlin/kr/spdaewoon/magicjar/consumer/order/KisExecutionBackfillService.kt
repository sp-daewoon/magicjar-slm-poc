package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.account.AccountSnapshotCoordinator
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisDailyCcldExecution
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisOrderInquireCcldHttpAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.user.AccountType
import kr.spdaewoon.magicjar.strategy.integrity.BackfillCancelledFillPolicy
import kr.spdaewoon.magicjar.strategy.integrity.CancelledFillInput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import reactor.core.scheduler.Schedulers
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * KIS WS 재연결 backfill 서비스 — ADR 0084 P0-3 (외부 코드 리뷰 결함 #4).
 *
 * ### 배경
 * KIS WS H0STCNI0/H0STCNI9 체결 통보는 disconnect 사이 발생한 체결을 누락 가능.
 * 본 service는 [KisWebSocketBridge.onReconnect] hook을 register 하여 재연결 직후 KIS REST 일별 체결 조회 발사 + 누락 체결 backfill.
 *
 * ### 흐름 (재연결 시점)
 *  1. KisWebSocketBridge.onReconnect 콜백 발사
 *  2. ConsumerUserKisCredentialJpaRepository에서 REAL 자격증명 사용자 enumeration
 *  3. 각 사용자별 [KisOrderInquireCcldHttpAdapter.fetchTodayExecutions] 호출 (당일 한정)
 *  4. 응답 row 각각:
 *     - eventId 합성: `BACKFILL:{kisOrderNo}:{ordTmd}` (cumulative 단위 — WS event_id와 다른 namespace)
 *     - [OrderExecutionRepository.existsByEventId] true 시 skip (이미 합류)
 *     - false 시:
 *       - OrderRequest 조회 (`findByKisOrderNo`) — 부재 시 pre-insert sentinel row 생성 (KisExecutionWsListener.preInsertOrderRequest 동일 정합)
 *       - OrderExecution INSERT (executedQuantity=totCcldQty, executedPrice=avgPrice, eventId=BACKFILL prefix)
 *       - audit FILL_RECEIVED + extra `{"source":"REST_BACKFILL"}`
 *
 * ### eventId namespace 분리 사유
 * WS 통보 event_id = `{odno}:{stckCntgHour}:{cntgQty}` — 분할 체결 1건 단위
 * REST backfill event_id = `BACKFILL:{odno}:{ordTmd}` — cumulative 총합 단위 (raw inquire-daily-ccld는 분할 체결을 cumulative `tot_ccld_qty`로 한번에 반환)
 *
 * 따라서 WS가 동일 주문의 4건 분할 체결 후 disconnect 발생 시:
 *  - 정상: WS event_id 4건 INSERT (qty=2+3+4+1 각각)
 *  - backfill: 1건 REST event_id INSERT (qty=10 cumulative)
 *  - audit trace로 운영자가 어느 path로 들어왔는지 확인 가능 (source field)
 *
 * **현 1차 정책**: WS path가 정상 합류 후 backfill이 들어오면 `existsByOrderId`로도 중복 가능. 본 PR 1차 정책은 단순 event_id UNIQUE만 의존 (cumulative vs 분할 mix 시 운영자 audit + 후속 backlog §62에서 reconcile)
 *
 * ### 호출 시점
 * - [ApplicationReadyEvent] 진입 시점에 [KisWebSocketBridge.onReconnect] 콜백 register (lazy wiring)
 * - 첫 연결도 본 hook 발사됨 → 부팅 직후 1회 backfill 실행 → 직전 종료 동안 누적 체결도 흡수
 *
 * ### Rate Limit (ADR 0037)
 * - REAL 사용자 N명 × 1회 KIS REST 호출 → N건/재연결
 * - WS 폭주(헬스체크 미스 → 무한 재연결) 시 KIS rate limit hit 가능 → backlog §62에서 throttle (예: 60s 내 1회 cap) 검토
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0068 §8 + ADR 0084 §2-8).
 */
@Component
class KisExecutionBackfillService(
    private val kisOrderInquireCcldHttpAdapter: KisOrderInquireCcldHttpAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val orderFilledKafkaProducer: OrderFilledKafkaProducer,
    private val accountBalanceChangedKafkaProducer: AccountBalanceChangedKafkaProducer,
    private val executionPersistenceService: ExecutionPersistenceService,
    private val webSocketBridge: KisWebSocketBridge,
    private val strategyPositionExecutionReplayServiceProvider: ObjectProvider<StrategyPositionExecutionReplayService>? = null,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.order.ws-backfill.enabled:true}")
    private val backfillEnabled: Boolean,
    @Value("\${magicjar.order.ws-backfill.min-interval-seconds:60}")
    private val backfillMinIntervalSeconds: Long = 60L,
    @Value("\${magicjar.order.cancelled-fill-reconcile.enabled:true}")
    private val cancelledFillReconcileEnabled: Boolean = true,
    @Value("\${magicjar.kis.execution-sync.fresh-window-seconds:30}")
    private val executionSyncFreshWindowSeconds: Long = 30L,
    @Value("\${magicjar.kis.execution-sync.failure-cooldown-seconds:10}")
    private val executionSyncFailureCooldownSeconds: Long = 10L,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(KisExecutionBackfillService::class.java)
    private val lastBackfillAtMs = AtomicLong(0L)
    private val syncLocks = ConcurrentHashMap<SyncKey, Any>()
    private val recentSyncResults = ConcurrentHashMap<SyncKey, CachedSyncResult>()

    private data class SyncKey(
        val userId: String,
        val accountType: AccountType,
        val verifyCredential: Boolean,
    )

    private data class CachedSyncResult(
        val result: KisExecutionSyncResult,
        val syncedAt: Instant,
    )

    companion object {
        /** ADR 0084 P0-3 — REST backfill eventId namespace prefix. */
        const val BACKFILL_EVENT_ID_PREFIX: String = "BACKFILL"

        fun buildBackfillEventId(
            kisOrderNo: String,
            ordTmd: String,
            totCcldQty: Int,
            avgPrice: BigDecimal? = null,
        ): String =
            listOfNotNull(
                BACKFILL_EVENT_ID_PREFIX,
                kisOrderNo,
                ordTmd,
                totCcldQty.toString(),
                avgPrice?.stripTrailingZeros()?.toPlainString(),
            ).joinToString(":")
    }

    /**
     * Spring Boot 완전 부팅 직후 KisWebSocketBridge.onReconnect 콜백 register.
     * Boot order: ApplicationReadyEvent는 모든 bean 합류 + 부팅 완료 후 발사 → wiring 안전.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun registerOnReconnect() {
        if (!backfillEnabled) {
            log.warn(
                "ADR 0084 P0-3 — WS 재연결 backfill 비활성 (magicjar.order.ws-backfill.enabled=false). 자본 audit risk 인지 후 운영 결정.",
            )
            return
        }
        webSocketBridge.onReconnect = { runBackfillSafely() }
        log.info("ADR 0084 P0-3 — WS 재연결 backfill hook register 완료")
        runBackfillSafely()
    }

    /**
     * 재연결 직후 backfill 실행 — 예외 catch + WS thread 안 죽이기.
     */
    internal fun runBackfillSafely() {
        if (Schedulers.isInNonBlockingThread()) {
            Schedulers.boundedElastic().schedule { runBackfillCatching() }
            return
        }
        runBackfillCatching()
    }

    private fun runBackfillCatching() {
        if (!acquireBackfillRunSlot()) return
        try {
            runBackfill()
        } catch (e: Exception) {
            log.error("ADR 0084 P0-3 — backfill 실행 실패: err={}", e.message, e)
        }
    }

    private fun acquireBackfillRunSlot(): Boolean {
        val minIntervalMs = backfillMinIntervalSeconds.coerceAtLeast(0L) * 1000L
        if (minIntervalMs == 0L) return true
        val nowMs = clock.millis()
        while (true) {
            val last = lastBackfillAtMs.get()
            if (last > 0 && nowMs - last < minIntervalMs) {
                log.warn(
                    "ADR 0084 P0-3 — backfill throttle skip: elapsedMs={} minIntervalMs={}",
                    nowMs - last,
                    minIntervalMs,
                )
                return false
            }
            if (lastBackfillAtMs.compareAndSet(last, nowMs)) return true
        }
    }

    /**
     * 본 backfill 진입점 — REAL 사용자별 KIS REST 일별 체결 조회 + 누락 INSERT.
     */
    internal fun runBackfill() {
        // 자격증명 사용자별 backfill 분기 (MOCK·REAL 모두 분기)
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val creds = credentialRepository.findAllByAccountType(accountType.name)
        if (creds.isEmpty()) {
            log.info(
                "ADR 0084 P0-3 — backfill skip: 자격증명 등록 사용자 0건 (accountType={})",
                accountType.name,
            )
            return
        }

        var totalFetched = 0
        var totalInserted = 0
        var totalFailed = 0
        creds.forEach { cred ->
            val userId = cred.id.userId
            val result = syncUserTodayExecutions(userId, accountType, verifyCredential = false)
            totalFetched += result.fetched
            totalInserted += result.inserted
            totalFailed += result.failed
        }

        log.info(
            "ADR 0084 P0-3 — backfill 완료: 사용자={}건 fetched={} inserted={} failed={} (skipped={})",
            creds.size,
            totalFetched,
            totalInserted,
            totalFailed,
            (totalFetched - totalInserted - totalFailed).coerceAtLeast(0),
        )
    }

    /**
     * REAL 주문 전 동기화 가드에서 사용하는 단일 사용자 KIS 체결 동기화.
     *
     * 성공 조건은 KIS 당일 체결조회가 성공했고, 각 응답 row가 DB에 INSERT 되거나 이미 반영되어
     * 손익 계산 재료로 사용할 수 있는 상태다. 하나라도 저장/매칭 실패하면 REAL 신규 주문은 fail-closed 한다.
     */
    internal fun syncUserTodayExecutions(
        userId: String,
        accountType: AccountType,
        verifyCredential: Boolean = true,
    ): KisExecutionSyncResult {
        val key = SyncKey(userId, accountType, verifyCredential)
        cachedSyncResultOrNull(key)?.let { return it }

        val lock = syncLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            cachedSyncResultOrNull(key)?.let { return@synchronized it }
            val result = syncUserTodayExecutionsUncached(userId, accountType, verifyCredential)
            recentSyncResults[key] = CachedSyncResult(result, Instant.now(clock))
            result
        }
    }

    private fun syncUserTodayExecutionsUncached(
        userId: String,
        accountType: AccountType,
        verifyCredential: Boolean = true,
    ): KisExecutionSyncResult {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        val execRepo = orderExecutionRepositoryProvider.getIfAvailable()
        if (orderRepo == null || execRepo == null) {
            log.warn(
                "KIS execution sync 실패 — repository 미합류: userId={} orderRepo={} execRepo={}",
                maskUserId(userId),
                orderRepo != null,
                execRepo != null,
            )
            return KisExecutionSyncResult.failure(userId, accountType, "REPOSITORY_NOT_WIRED")
        }

        if (verifyCredential) {
            val hasCredential =
                try {
                    credentialRepository.findAllByAccountType(accountType.name).any { it.id.userId == userId }
                } catch (e: Exception) {
                    log.error(
                        "KIS execution sync 자격증명 조회 실패: userId={} accountType={} err={}",
                        maskUserId(userId),
                        accountType.name,
                        e.message,
                    )
                    return KisExecutionSyncResult.failure(userId, accountType, "CREDENTIAL_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}")
                }
            if (!hasCredential) {
                log.warn("KIS execution sync 실패 — 자격증명 없음: userId={} accountType={}", maskUserId(userId), accountType.name)
                return KisExecutionSyncResult.failure(userId, accountType, "CREDENTIAL_NOT_FOUND")
            }
        }

        val executions =
            try {
                kisOrderInquireCcldHttpAdapter.fetchTodayExecutions(userId, accountType)
            } catch (e: Exception) {
                log.error("KIS execution sync REST 호출 실패: userId={} accountType={} err={}", maskUserId(userId), accountType.name, e.message)
                return KisExecutionSyncResult.failure(userId, accountType, "KIS_FETCH_FAILED:${e.message ?: "UNKNOWN"}")
            }

        var inserted = 0
        var alreadySynced = 0
        var failed = 0
        executions.forEach { exec ->
            when (persistBackfillRowDetailed(orderRepo, execRepo, exec, userId, accountType)) {
                BackfillRowStatus.INSERTED -> inserted++
                BackfillRowStatus.ALREADY_SYNCED -> alreadySynced++
                BackfillRowStatus.FAILED -> failed++
            }
        }

        val result =
            KisExecutionSyncResult(
                userId = userId,
                accountType = accountType,
                fetched = executions.size,
                inserted = inserted,
                alreadySynced = alreadySynced,
                failed = failed,
                error = null,
                syncedAt = Instant.now(clock),
            )
        if (!result.isComplete) {
            log.error(
                "KIS execution sync 불완전 — userId={} accountType={} fetched={} inserted={} alreadySynced={} failed={}",
                maskUserId(userId),
                accountType.name,
                result.fetched,
                result.inserted,
                result.alreadySynced,
                result.failed,
            )
        }
        return result
    }

    private fun cachedSyncResultOrNull(key: SyncKey): KisExecutionSyncResult? {
        val cached = recentSyncResults[key] ?: return null
        val ageSeconds = Duration.between(cached.syncedAt, Instant.now(clock)).seconds
        val ttlSeconds =
            if (cached.result.isComplete) {
                executionSyncFreshWindowSeconds
            } else {
                executionSyncFailureCooldownSeconds
            }.coerceAtLeast(0)
        if (ttlSeconds <= 0 || ageSeconds > ttlSeconds) {
            recentSyncResults.remove(key, cached)
            return null
        }
        log.info(
            "KIS execution sync singleflight cache HIT: userId={} accountType={} complete={} ageSec={} ttlSec={}",
            maskUserId(key.userId),
            key.accountType.name,
            cached.result.isComplete,
            ageSeconds,
            ttlSeconds,
        )
        return cached.result
    }

    /**
     * 1건의 backfill row 영속 — eventId UNIQUE 가드 + OrderRequest pre-insert 합류.
     *
     * @return true = INSERT 합류 / false = skip (dedup 또는 영속 실패)
     */
    internal fun persistBackfillRow(
        orderRepo: OrderRequestRepository,
        execRepo: OrderExecutionRepository,
        exec: KisDailyCcldExecution,
        userId: String,
        accountType: AccountType,
    ): Boolean = persistBackfillRowDetailed(orderRepo, execRepo, exec, userId, accountType) == BackfillRowStatus.INSERTED

    private fun persistBackfillRowDetailed(
        orderRepo: OrderRequestRepository,
        execRepo: OrderExecutionRepository,
        exec: KisDailyCcldExecution,
        userId: String,
        accountType: AccountType,
    ): BackfillRowStatus {
        val eventId = buildBackfillEventId(exec.kisOrderNo, exec.ordTmd, exec.totCcldQty, exec.avgPrice)

        val request =
            try {
                orderRepo.findByKisOrderNo(exec.kisOrderNo)
            } catch (e: Exception) {
                log.error("backfill findByKisOrderNo 실패: kisOrderNo={} err={}", exec.kisOrderNo, e.message)
                null
            } ?: preInsertOrderRequestForBackfill(orderRepo, exec, userId, accountType)

        if (request == null || request.id == null) {
            log.warn("backfill skip — OrderRequest pre-insert 실패: kisOrderNo={}", exec.kisOrderNo)
            return BackfillRowStatus.FAILED
        }

        val orderId = request.id ?: return BackfillRowStatus.FAILED

        // 1단계: eventId UNIQUE 가드
        val alreadyPersisted =
            try {
                execRepo.existsByEventId(eventId)
            } catch (e: Exception) {
                log.error("backfill existsByEventId 실패 — skip (자본 안전): eventId={} err={}", eventId, e.message)
                return BackfillRowStatus.FAILED
            }
        if (alreadyPersisted) {
            log.debug("backfill skip (event_id dedup): eventId={}", eventId)
            syncOrderStatusAfterBackfill(
                orderRepo,
                execRepo,
                request,
                null,
                emitFilledEvent = false,
                preserveStatus = request.status.isTerminalForBackfill(),
            )
            replayStrategyPositionIfMissing(request, "REST_BACKFILL_ALREADY_SYNCED")
            return BackfillRowStatus.ALREADY_SYNCED
        }

        val currentExecutedQty =
            try {
                execRepo.sumExecutedQuantityByOrder(orderId)
            } catch (e: Exception) {
                log.error("backfill sumExecutedQuantityByOrder 실패 — skip: orderId={} err={}", orderId, e.message)
                return BackfillRowStatus.FAILED
            }

        // ADR 0115 §2-P2-② (G5 — 취소-체결 race) — terminal(특히 CANCELLED) 주문이라도 KIS 실체결 누락이면 기록.
        //   execution INSERT 는 status 와 독립. 단 terminal 이면 status·completed_at 은 보존(preserveStatus).
        val terminal = request.status.isTerminalForBackfill()
        val decision =
            BackfillCancelledFillPolicy.decide(
                CancelledFillInput(
                    kisTotalCcldQty = exec.totCcldQty,
                    currentExecutedQty = currentExecutedQty,
                    terminal = terminal,
                    enabled = cancelledFillReconcileEnabled,
                ),
            )
        val recordDecision = decision as? BackfillCancelledFillPolicy.Decision.Record
        if (recordDecision == null) {
            log.info(
                "backfill skip — KIS 누적체결 이하 이미 반영: orderId={} kisOrderNo={} current={} kisTotal={}",
                orderId,
                exec.kisOrderNo,
                currentExecutedQty,
                exec.totCcldQty,
            )
            syncOrderStatusAfterBackfill(orderRepo, execRepo, request, null, emitFilledEvent = false, preserveStatus = terminal)
            replayStrategyPositionIfMissing(request, "REST_BACKFILL_DELTA_ALREADY_APPLIED")
            return BackfillRowStatus.ALREADY_SYNCED
        }
        val deltaQty = recordDecision.deltaQuantity
        if (terminal) {
            log.warn(
                "ADR 0115 §2-P2-② — terminal 주문 KIS 실체결 누락 흡수(status 보존): orderId={} kisOrderNo={} status={} current={} kisTotal={} delta={}",
                orderId,
                exec.kisOrderNo,
                request.status,
                currentExecutedQty,
                exec.totCcldQty,
                deltaQty,
            )
        }

        // 3단계: OrderExecution INSERT — KIS 누적체결에서 이미 반영된 수량을 뺀 delta만 저장.
        val now = Instant.now(clock)
        val saved =
            try {
                executionPersistenceService.saveAndApplyPosition(
                    request,
                    OrderExecution(
                        orderId = orderId,
                        userId = request.userId,
                        kisOrderNo = exec.kisOrderNo,
                        executedQuantity = deltaQty,
                        executedPrice = exec.avgPrice,
                        executedAt = exec.executedAt,
                        eventId = eventId,
                        createdAt = now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "backfill execution persistence 실패: kisOrderNo={} eventId={} err={}",
                    exec.kisOrderNo,
                    eventId,
                    e.message,
                )
                return BackfillRowStatus.FAILED
            }

        // 4단계: audit FILL_RECEIVED + source=REST_BACKFILL
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.FILL_RECEIVED,
                    payloadJson =
                        """{"source":"REST_BACKFILL","eventId":"$eventId","kisOrderNo":"${exec.kisOrderNo}",""" +
                            """"deltaQty":$deltaQty,"totCcldQty":${exec.totCcldQty},"avgPrice":"${exec.avgPrice.toPlainString()}"}""",
                    createdAt = now,
                ),
            )
        }

        log.info(
            "ADR 0084 P0-3 — backfill INSERT: orderId={} kisOrderNo={} eventId={} qty={} price={} executionId={}",
            request.id,
            exec.kisOrderNo,
            eventId,
            deltaQty,
            exec.avgPrice.toPlainString(),
            saved.id,
        )
        invalidateAccountSnapshot(request, reason = "REST_BACKFILL_FILLED", kisOrderNo = exec.kisOrderNo)
        syncOrderStatusAfterBackfill(
            orderRepo,
            execRepo,
            request,
            saved,
            emitFilledEvent = true,
            preserveStatus = recordDecision.preserveStatus,
        )
        replayStrategyPositionIfMissing(request, "REST_BACKFILL_INSERTED")
        return BackfillRowStatus.INSERTED
    }

    private fun replayStrategyPositionIfMissing(
        request: OrderRequest,
        reason: String,
    ) {
        val replayService = strategyPositionExecutionReplayServiceProvider?.getIfAvailable() ?: return
        runCatching { replayService.replayIfMissing(request, reason) }
            .onFailure { e ->
                log.warn(
                    "strategy_position replay 실패 — backfill 체결 sync는 유지: orderId={} reason={} err={}",
                    request.id,
                    reason,
                    e.message,
                )
            }
    }

    private fun invalidateAccountSnapshot(
        request: OrderRequest,
        reason: String,
        kisOrderNo: String?,
    ) {
        accountSnapshotCoordinatorProvider
            ?.getIfAvailable()
            ?.invalidate(
                userId = request.userId,
                mode = request.mode,
                reason = reason,
                triggerKisOrderNo = kisOrderNo,
            )
    }

    private fun syncOrderStatusAfterBackfill(
        orderRepo: OrderRequestRepository,
        execRepo: OrderExecutionRepository,
        request: OrderRequest,
        execution: OrderExecution?,
        emitFilledEvent: Boolean,
        preserveStatus: Boolean = false,
    ) {
        val orderId = request.id ?: return

        // ADR 0084 P0-3 결함 fix (backlog §82-1, 2026-06-04) +
        // ADR 0115 §2-P2-② (2026-06-10) — terminal 주문 status·completed_at 보존.
        //   기존: execution == null && terminal 일 때만 skip(재마킹 회피). 그러나 G5(취소-체결 race)는
        //   terminal(CANCELLED) 주문에 **신규 체결 delta 가 있는** 경우 — 이때 execution!=null 이라 종전엔
        //   PARTIALLY_FILLED 로 status 가 덮어써졌다(CANCELLED 소실). 이제 preserveStatus=true(terminal) 면
        //   execution 유무와 무관하게 status 전이를 막아 CANCELLED 를 보존한다.
        //   체결 기록(order_execution INSERT)은 본 메서드 호출 **이전**에 이미 완료됐으므로 누락 0 —
        //   status 보존과 체결 흡수가 분리된다(spec §4-② "execution INSERT 는 status 와 독립").
        if (preserveStatus) {
            log.debug(
                "backfill status sync skip — terminal status 보존(체결은 이미 기록): orderId={} status={} execution={}",
                orderId,
                request.status,
                execution?.id,
            )
            return
        }

        val totalExecuted =
            try {
                execRepo.sumExecutedQuantityByOrder(orderId)
            } catch (e: Exception) {
                log.error("backfill status sync sum 실패: orderId={} err={}", orderId, e.message)
                return
            }
        if (totalExecuted <= 0) return

        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        when {
            totalExecuted < request.quantity -> {
                val updated =
                    try {
                        orderRepo.updateStatus(orderId, OrderStatus.PARTIALLY_FILLED, null)
                    } catch (e: Exception) {
                        log.error("backfill updateStatus PARTIALLY_FILLED 실패: orderId={} err={}", orderId, e.message)
                        request.copy(status = OrderStatus.PARTIALLY_FILLED)
                    } ?: request.copy(status = OrderStatus.PARTIALLY_FILLED)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = orderId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.PARTIAL_FILL,
                            payloadJson = """{"source":"REST_BACKFILL","sum":$totalExecuted,"qty":${request.quantity}}""",
                            createdAt = now,
                        ),
                    )
                }
                publishBackfillFillEvents(updated, execution, partial = true, emitFilledEvent = emitFilledEvent)
            }
            totalExecuted == request.quantity -> {
                val updated =
                    try {
                        orderRepo.markCompleted(orderId, now, OrderStatus.FILLED)
                    } catch (e: Exception) {
                        log.error("backfill markCompleted FILLED 실패: orderId={} err={}", orderId, e.message)
                        request.copy(status = OrderStatus.FILLED, completedAt = now)
                    } ?: request.copy(status = OrderStatus.FILLED, completedAt = now)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = orderId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.FULL_FILL,
                            payloadJson = """{"source":"REST_BACKFILL","sum":$totalExecuted,"qty":${request.quantity}}""",
                            createdAt = now,
                        ),
                    )
                }
                publishBackfillFillEvents(updated, execution, partial = false, emitFilledEvent = emitFilledEvent)
            }
            else -> {
                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = orderId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.EXECUTION_OVERFLOW,
                            payloadJson = """{"source":"REST_BACKFILL","sum":$totalExecuted,"qty":${request.quantity}}""",
                            createdAt = now,
                        ),
                    )
                }
                log.error("backfill EXECUTION_OVERFLOW: orderId={} sum={} > qty={}", orderId, totalExecuted, request.quantity)
            }
        }
    }

    private fun publishBackfillFillEvents(
        request: OrderRequest,
        execution: OrderExecution?,
        partial: Boolean,
        emitFilledEvent: Boolean,
    ) {
        if (!emitFilledEvent) return
        if (execution != null) {
            orderFilledKafkaProducer.publish(request, execution, partial)
        }
        accountBalanceChangedKafkaProducer.publish(
            userId = request.userId,
            mode = request.mode,
            reason = AccountBalanceChangedKafkaProducer.REASON_FILLED,
            triggerKisOrderNo = request.kisOrderNo,
        )
    }

    /**
     * backfill 시점 OrderRequest stub row 생성 — KisExecutionWsListener.preInsertOrderRequest 동일 정합.
     *
     * sentinel: signalId=0L, strategyId=0L, priceType=MARKET, status=ACK, source=EXTERNAL.
     *
     * ### user/mode 정합 (WS path 와 다른 점)
     * backfill 은 credential enumeration 으로 진입하므로 `userId`(내부) + `accountType` 이 이미 정확하다
     * (WS path 처럼 ACNT_NO 역조회 불필요). 따라서 mode 는 accountType 에서 직접 환산.
     *
     * ### source = EXTERNAL (2026-06-05 fix)
     * 본 메서드는 `findByKisOrderNo == null` 일 때만 호출 — 내부 order_request 가 없는 KIS 체결은
     * 우리가 발의하지 않은 외부(HTS·MTS) 주문이므로 EXTERNAL 분류 (구 default AUTO 결함 정정).
     */
    internal fun preInsertOrderRequestForBackfill(
        orderRepo: OrderRequestRepository,
        exec: KisDailyCcldExecution,
        userId: String,
        accountType: AccountType,
    ): OrderRequest? {
        val now = Instant.now(clock)
        val sentinelKey = "REST_BACKFILL_PREINSERT:${exec.kisOrderNo}"

        val existing =
            try {
                orderRepo.findByIdempotencyKey(sentinelKey)
            } catch (e: Exception) {
                log.error("backfill preInsert findByIdempotencyKey 실패: kisOrderNo={} err={}", exec.kisOrderNo, e.message)
                null
            }
        if (existing != null) {
            return existing
        }

        val side =
            if (exec.sellBuyCls == "01") {
                kr.spdaewoon.magicjar.domain.order.execution.OrderSide.SELL
            } else {
                kr.spdaewoon.magicjar.domain.order.execution.OrderSide.BUY
            }
        val stub =
            OrderRequest(
                userId = userId,
                signalId = 0L,
                strategyId = 0L,
                code = exec.code,
                side = side,
                priceType = kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType.MARKET,
                quantity = exec.ordQty.coerceAtLeast(exec.totCcldQty),
                limitPrice = null,
                status = OrderStatus.ACK,
                mode =
                    if (accountType == AccountType.REAL) {
                        kr.spdaewoon.magicjar.domain.order.execution.TradingMode.REAL
                    } else {
                        kr.spdaewoon.magicjar.domain.order.execution.TradingMode.MOCK
                    },
                kisOrderNo = exec.kisOrderNo,
                idempotencyKey = sentinelKey,
                requestedAt = now,
                acknowledgedAt = now,
                // 2026-06-05 fix — 내부 order_request 부재(findByKisOrderNo == null)인 KIS 일별체결은
                // 우리 시스템이 발의하지 않은 외부(HTS·MTS) 주문 → AUTO 가 아니라 EXTERNAL 로 분류 (감사 명확성).
                source = kr.spdaewoon.magicjar.domain.order.execution.OrderSource.EXTERNAL,
                // ADR 0115 §2-P2-⑥ — 백필 stub strategy_id=0 sentinel을 EXTERNAL 출처로 명시(귀속 불능을 sentinel로 위장 폐지).
                orderTrigger = kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger.EXTERNAL,
            )
        return try {
            orderRepo.save(stub)
        } catch (e: Exception) {
            log.error("backfill preInsert save 실패: kisOrderNo={} err={}", exec.kisOrderNo, e.message)
            null
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * backfill 재마킹 가드용 종결 상태 판정 (backlog §82-1).
     *
     * FILLED·CANCELLED·REJECTED·EXPIRED 는 더 이상 상태·completed_at·audit를 건드리면 안 되는 최종 상태.
     * PENDING_RECONCILE 는 의도적으로 제외 — reconciliation worker / backfill 후속 정상화 경로가 필요 (ADR 0084 P0-2).
     */
    private fun OrderStatus.isTerminalForBackfill(): Boolean =
        this == OrderStatus.FILLED ||
            this == OrderStatus.CANCELLED ||
            this == OrderStatus.REJECTED ||
            this == OrderStatus.EXPIRED

    private enum class BackfillRowStatus {
        INSERTED,
        ALREADY_SYNCED,
        FAILED,
    }
}

data class KisExecutionSyncResult(
    val userId: String,
    val accountType: AccountType,
    val fetched: Int,
    val inserted: Int,
    val alreadySynced: Int,
    val failed: Int,
    val error: String?,
    val syncedAt: Instant,
) {
    val isComplete: Boolean
        get() = error == null && failed == 0

    val reasonCode: String
        get() =
            when {
                error != null -> error
                failed > 0 -> "BACKFILL_INCOMPLETE:fetched=$fetched,failed=$failed"
                else -> "OK"
            }

    companion object {
        fun failure(
            userId: String,
            accountType: AccountType,
            error: String,
            now: Instant = Instant.now(),
        ): KisExecutionSyncResult =
            KisExecutionSyncResult(
                userId = userId,
                accountType = accountType,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 1,
                error = error,
                syncedAt = now,
            )
    }
}
