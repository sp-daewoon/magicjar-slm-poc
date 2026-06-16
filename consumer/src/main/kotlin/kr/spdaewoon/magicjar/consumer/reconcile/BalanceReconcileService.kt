package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.reconcile.BalanceReconciler
import kr.spdaewoon.magicjar.domain.reconcile.HoldingDiff
import kr.spdaewoon.magicjar.domain.reconcile.InternalHolding
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileResult
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import kr.spdaewoon.magicjar.domain.reconcile.port.InternalPositionQueryPort
import kr.spdaewoon.magicjar.domain.reconcile.port.PositionUpsertPort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileKisBalancePort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileRunRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Reconcile 도메인 흐름 orchestration (ADR 0041·0070 §2-2 + §2-11).
 *
 * 본 서비스는 BalanceReconcileJob(@Scheduled) + ManualReconcileService(REST) 양쪽 공유 — 동일 흐름.
 *
 * ### 흐름 (ADR 0070 §2-11 자본 보호)
 * ```
 * 1. reconcile_run INSERT(status=RUNNING) — Phase 2 어댑터 합류 시 영속, 미합류 시 in-memory ID=null
 * 2. KIS REST 잔고 흡수 (페이징) — ReconcileKisBalancePort.fetchAllBalances
 * 3. 내부 DB position 조회 — InternalPositionQueryPort.findCurrentByUser (어댑터 부재 시 emptyList)
 * 4. BalanceReconciler.compare(kis, internal) → ReconcileResult
 * 5-A. Reconciled → reconcile_run UPDATE(status=RECONCILED) + reconcile.run.v1 publish (audit)
 * 5-B. Drifted → 종목별 reconcile.drift.v1 publish + DB position upsert (KIS 신뢰원) +
 *                reconcile_run UPDATE(status=DRIFT, drift_count=N) + reconcile.run.v1 publish
 *                (KillSwitchAutoToggler가 reconcile.drift.v1 listen → engage)
 * 5-C. PriceDrifted → reconcile_run UPDATE(status=DRIFT_PRICE) + reconcile.run.v1 publish (audit only)
 * 6. (예외) reconcile_run UPDATE(status=ERROR, last_error) + reconcile.run.v1 publish
 * ```
 *
 * ### Phase 2 graceful fallback (헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * - `ReconcileRunRepository` 미합류: in-memory ReconcileRun(id=null)로 진행 — log.warn 1회
 * - `InternalPositionQueryPort` 미합류: emptyList 가정 — KIS 보유 종목 전체가 drift로 분류됨 (사실상 Bootstrap 패턴)
 * - `PositionUpsertPort` 미합류: log.warn — KIS 신뢰원 갱신 skip (Kafka publish는 계속)
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Service
class BalanceReconcileService(
    private val kisBalancePort: ReconcileKisBalancePort,
    private val eventPublisher: ReconcileEventPublisher,
    private val reconcileRunRepositoryProvider: ObjectProvider<ReconcileRunRepository>,
    private val internalPositionQueryPortProvider: ObjectProvider<InternalPositionQueryPort>,
    private val positionUpsertPortProvider: ObjectProvider<PositionUpsertPort>,
    private val systemEventLogger: SystemEventLogger,
    /**
     * KillSwitch race-safe guard A (Pending Order Exclusion) — drift publish 직전 종목별 필터.
     *
     * `OrderRequestRepository.findOpenByUserId` 호출로 status ∈ {REQUESTED, ACK, PARTIALLY_FILLED}
     * 종목 set 획득 → drift symbol에 포함되면 publish skip (KillSwitchAutoToggler ON 차단).
     *
     * ObjectProvider Phase 2 graceful — 어댑터 부재 시 emptySet 가정 (skip 0).
     */
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
) {
    private val log = LoggerFactory.getLogger(BalanceReconcileService::class.java)

    companion object {
        /** SystemEvent actor — P1 catalog (spec §6.1 emit point #3) */
        private const val SYSTEM_EVENT_ACTOR = "BalanceReconcileService"
    }

    /**
     * 단일 사용자 reconcile 1회 실행.
     *
     * @return ReconcileRun (최종 status·driftCount·lastError 반영). id는 Phase 2 어댑터 부재 시 null.
     */
    fun reconcileUser(
        userId: String,
        triggerSource: ReconcileTriggerSource,
    ): ReconcileRun {
        val startedAt = Instant.now()
        var run =
            ReconcileRun(
                userId = userId,
                startedAt = startedAt,
                status = ReconcileStatus.RUNNING,
                triggerSource = triggerSource,
            )

        // Step 1 — RUNNING INSERT
        run = saveOrInMemory(run)
        eventPublisher.publishRun(run)

        return try {
            // Step 2 — KIS REST 흡수
            val kisHoldings = kisBalancePort.fetchAllBalances(userId)
            log.info(
                "reconcile KIS 흡수 완료: userId={} holdings={}건",
                maskUserId(userId),
                kisHoldings.size,
            )

            // Step 3 — 내부 DB position 조회
            val internalHoldings = fetchInternalHoldings(userId)
            log.debug(
                "reconcile internal 조회: userId={} holdings={}건",
                maskUserId(userId),
                internalHoldings.size,
            )

            // Step 4 — diff 계산
            val result = BalanceReconciler.compare(kisHoldings, internalHoldings)

            // ★ SystemEvent emit point #3 (P1 spec §6.1) — RECONCILE_DRIFT_DETECTED
            // Drifted 분기 진입 시 1회 emit (WARN severity, 자본 사고 audit).
            // PriceDrifted 단독은 자본 영향 없음 (avg price 차이만) → emit skip.
            if (result is ReconcileResult.Drifted) {
                emitDriftDetected(
                    userId = userId,
                    kisHoldingsSize = kisHoldings.size,
                    dbPositionsSize = internalHoldings.size,
                    driftCount = result.driftedHoldings.size,
                )
            }

            // Step 5 — 분기 처리
            val finalRun = handleResult(run, result, userId, kisHoldings)
            finalRun
        } catch (e: Exception) {
            log.error("reconcile 실패: userId={} err={}", maskUserId(userId), e.message, e)
            val errored =
                run.copy(
                    finishedAt = Instant.now(),
                    status = ReconcileStatus.ERROR,
                    lastError = e.message?.take(500) ?: "unknown",
                )
            val saved = saveOrInMemory(errored)
            eventPublisher.publishRun(saved)
            saved
        }
    }

    private fun handleResult(
        run: ReconcileRun,
        result: ReconcileResult,
        userId: String,
        kisHoldings: List<ReconcileHolding>,
    ): ReconcileRun {
        val now = Instant.now()
        return when (result) {
            is ReconcileResult.Reconciled -> {
                val ok =
                    run.copy(
                        finishedAt = now,
                        status = ReconcileStatus.RECONCILED,
                        driftCount = 0,
                    )
                val saved = saveOrInMemory(ok)
                eventPublisher.publishRun(saved)
                log.info(
                    "reconcile RECONCILED: userId={} totalHoldings={}",
                    maskUserId(userId),
                    result.totalHoldings,
                )
                saved
            }
            is ReconcileResult.Drifted -> {
                val driftCount = result.driftedHoldings.size
                val drifted =
                    run.copy(
                        finishedAt = now,
                        status = ReconcileStatus.DRIFT,
                        driftCount = driftCount,
                    )
                val saved = saveOrInMemory(drifted)

                // KillSwitch race-safe guard A (Pending Order Exclusion) — drift publish 직전 종목 필터.
                // status ∈ {REQUESTED, ACK, PARTIALLY_FILLED} 종목은 KIS 인덱스 반영 race 가능 → publish skip.
                // upsertFromKis는 정상 수행 (DB 정합은 그대로 KIS 신뢰원 갱신, 다만 자동 KillSwitch ON 차단).
                val pendingSymbols = fetchPendingSymbols(userId)

                // KIS 신뢰원으로 내부 position upsert
                val kisBySymbol = kisHoldings.associateBy { it.symbol }
                result.driftedHoldings.forEach { diff ->
                    val kisHolding = kisBySymbol[diff.symbol]
                    if (kisHolding != null) {
                        upsertFromKis(userId, kisHolding)
                    } else if (diff.kisQty == 0) {
                        // 반대 누락 (내부 있는데 KIS=0) — 내부 0주 upsert 필요. ReconcileHolding 구조상 kisQty=0인
                        // holding은 KIS 응답에 없으므로 stub upsert (T-O `PositionUpdatePort` reuse 합류 시 zero-out 패턴)
                        upsertFromKis(
                            userId,
                            ReconcileHolding(
                                symbol = diff.symbol,
                                quantity = 0,
                                avgPrice = java.math.BigDecimal.ZERO,
                            ),
                        )
                    }
                    if (diff.symbol in pendingSymbols) {
                        log.info(
                            "reconcile drift publish skip — pending order 존재 (race-safe guard A): " +
                                "userId={} symbol={} qtyDiff={}",
                            maskUserId(userId),
                            diff.symbol,
                            diff.qtyDiff,
                        )
                    } else {
                        eventPublisher.publishDrift(userId, saved.id, diff)
                    }
                }

                // priceDrift 종목은 audit (publish drift는 임계 도달 종목만)
                result.priceDriftedHoldings.forEach { priceDiff ->
                    log.info(
                        "reconcile DRIFT_PRICE (audit only): userId={} symbol={} ratio={}",
                        maskUserId(userId),
                        priceDiff.symbol,
                        priceDiff.avgPriceDiffRatio,
                    )
                }

                eventPublisher.publishRun(saved)
                log.warn(
                    "reconcile DRIFT (자본 사고): userId={} driftCount={}",
                    maskUserId(userId),
                    driftCount,
                )
                saved
            }
            is ReconcileResult.PriceDrifted -> {
                val priceDrifted =
                    run.copy(
                        finishedAt = now,
                        status = ReconcileStatus.DRIFT_PRICE,
                        driftCount = 0,
                    )
                val saved = saveOrInMemory(priceDrifted)
                result.priceDriftedHoldings.forEach { priceDiff ->
                    log.info(
                        "reconcile DRIFT_PRICE (audit only): userId={} symbol={} ratio={}",
                        maskUserId(userId),
                        priceDiff.symbol,
                        priceDiff.avgPriceDiffRatio,
                    )
                }
                eventPublisher.publishRun(saved)
                saved
            }
        }
    }

    /**
     * KillSwitch race-safe guard A — pending order(REQUESTED·ACK·PARTIALLY_FILLED) 종목 set.
     *
     * Phase 2 graceful — `OrderRequestRepository` 어댑터 부재 시 emptySet (skip 0).
     * 호출 실패 시도 graceful — emptySet fallback + log.warn (drift publish는 정상 진행).
     */
    private fun fetchPendingSymbols(userId: String): Set<String> {
        val repo = orderRequestRepositoryProvider.getIfAvailable() ?: return emptySet()
        return runCatching {
            repo
                .findOpenByUserId(userId)
                .map { it.code }
                .toSet()
        }.getOrElse { e ->
            log.warn(
                "OrderRequestRepository.findOpenByUserId 호출 실패 — race-safe guard A skip: userId={} err={}",
                maskUserId(userId),
                e.message,
            )
            emptySet()
        }
    }

    private fun fetchInternalHoldings(userId: String): List<InternalHolding> {
        val port = internalPositionQueryPortProvider.getIfAvailable()
        if (port == null) {
            log.warn(
                "InternalPositionQueryPort 미합류 (Phase 2 미완) — emptyList 가정: userId={}",
                maskUserId(userId),
            )
            return emptyList()
        }
        return try {
            port.findCurrentByUser(userId)
        } catch (e: Exception) {
            log.warn("InternalPositionQueryPort 호출 실패: userId={} err={}", maskUserId(userId), e.message)
            emptyList()
        }
    }

    private fun upsertFromKis(
        userId: String,
        holding: ReconcileHolding,
    ) {
        val port = positionUpsertPortProvider.getIfAvailable()
        if (port == null) {
            log.warn(
                "PositionUpsertPort 미합류 (Phase 2 미완) — upsert skip: userId={} symbol={}",
                maskUserId(userId),
                holding.symbol,
            )
            return
        }
        try {
            port.upsertFromKis(userId, holding)
        } catch (e: Exception) {
            log.error(
                "PositionUpsertPort.upsertFromKis 실패: userId={} symbol={} err={}",
                maskUserId(userId),
                holding.symbol,
                e.message,
            )
        }
    }

    private fun saveOrInMemory(run: ReconcileRun): ReconcileRun {
        val repo = reconcileRunRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn(
                "ReconcileRunRepository 미합류 (Phase 2 미완) — in-memory id=null: userId={}",
                maskUserId(run.userId),
            )
            return run
        }
        return try {
            repo.save(run)
        } catch (e: Exception) {
            log.error("ReconcileRunRepository.save 실패: userId={} err={}", maskUserId(run.userId), e.message)
            run
        }
    }

    /**
     * Test fixture 진입점 — 단일 종목 diff publish 검증용 (외부 호출 X).
     */
    internal fun publishDriftForTest(
        userId: String,
        diff: HoldingDiff,
    ) {
        eventPublisher.publishDrift(userId, null, diff)
    }

    /**
     * SystemEvent emit graceful wrap — RECONCILE_DRIFT_DETECTED (spec §6.1 emit point #3).
     *
     * runCatching 의무 — emit 자체 실패가 reconcile 본체 cascade 차단 방지.
     * SystemEventLoggerAdapter도 내부 graceful이지만, port 계약 (spec §6.1)에 따라 caller 측도 추가 wrap.
     */
    private fun emitDriftDetected(
        userId: String,
        kisHoldingsSize: Int,
        dbPositionsSize: Int,
        driftCount: Int,
    ) {
        runCatching {
            systemEventLogger.emit(
                SystemEvent(
                    category = SystemEventCategory.RECONCILE,
                    severity = SystemEventSeverity.WARN,
                    actor = SYSTEM_EVENT_ACTOR,
                    action = "RECONCILE_DRIFT_DETECTED",
                    userId = userId,
                    detailJson =
                        """{"kisHoldings":$kisHoldingsSize,"dbPositions":$dbPositionsSize,"driftCount":$driftCount}""",
                ),
            )
        }.onFailure { e ->
            log.error("SystemEventLogger.emit 실패: action=RECONCILE_DRIFT_DETECTED err={}", e.message)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
