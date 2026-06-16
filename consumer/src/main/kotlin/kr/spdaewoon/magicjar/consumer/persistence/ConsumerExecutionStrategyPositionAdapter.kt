package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.strategy.integrity.DriftCorrectionInput
import kr.spdaewoon.magicjar.strategy.integrity.DriftCorrectionResult
import kr.spdaewoon.magicjar.strategy.integrity.LedgerAttributedDriftCorrection
import kr.spdaewoon.magicjar.strategy.integrity.LedgerAttribution
import kr.spdaewoon.magicjar.strategy.integrity.LedgerDriftInput
import kr.spdaewoon.magicjar.strategy.integrity.PositionDriftCorrection
import kr.spdaewoon.magicjar.strategy.integrity.RebuildExecution
import kr.spdaewoon.magicjar.strategy.integrity.RebuildInput
import kr.spdaewoon.magicjar.strategy.integrity.RebuildSide
import kr.spdaewoon.magicjar.strategy.integrity.SingleStrategyOrderEvidence
import kr.spdaewoon.magicjar.strategy.integrity.StrategyPositionLeg
import kr.spdaewoon.magicjar.strategy.integrity.StrategyPositionRebuild
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 체결 직후 전략별 포지션 귀속(strategy_position) upsert 어댑터 (ADR 0110, 분할매수 R2-2).
 *
 * 기존 [ConsumerExecutionPositionUpdateAdapter](user+code 합산 position)와 **대칭** — [ExecutionPersistenceService]
 * 의 같은 transaction boundary 에서 호출되어 strategy_position lot ledger 를 동반 갱신한다.
 *
 * ## 합류점 결정 (spec §5 흐름)
 * `KisExecutionWsListener.handleExecution` → [ExecutionPersistenceService.saveAndApplyPosition]
 * (order_execution INSERT + position upsert 묶음, event_id dedup 뒤) 에 본 어댑터를 추가 호출한다.
 * 멱등성은 그 호출 측의 event_id dedup 가드가 보장(같은 체결 1회만 도달).
 *
 * ## 평단/실현손익 수식 (spec §3-1 — [ConsumerExecutionPositionUpdateAdapter] BUY/SELL 수식 정합)
 * - BUY: `avgPrice = (avgPrice*qty + fillPrice*fillQty)/(qty+fillQty)`, `quantity += fillQty`, `filledLots += 1`.
 * - SELL: `realizedPnl += (fillPrice - avgPrice)*sellQty`, `quantity -= sellQty`.
 *   reason `PARTIAL_TAKE_PROFIT#{k}` → `executedExitStages += k`(체결 시점 마킹).
 *   `quantity == 0` → `avgPrice`·`filledLots`·`executedExitStages` reset(행 유지).
 *
 * ## skip 조건
 * - `strategyId == 0`(MANUAL sentinel — 전략 귀속 없음) / 체결 수량 ≤ 0.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 어댑터 (ADR 0023 §2-4 정합)
 */
@Component
class ConsumerExecutionStrategyPositionAdapter(
    private val strategyPositionPort: StrategyPositionPort,
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    private val positionJpaRepository: PositionJpaRepository,
    private val orderRequestJpaRepository: ConsumerOrderRequestJpaRepository,
    private val signalJpaRepository: ConsumerSignalJpaRepository,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    @Value("\${magicjar.position.drift-auto-correct.enabled:true}")
    private val driftAutoCorrectEnabled: Boolean = true,
    @Value("\${magicjar.position.ledger-rebuild.enabled:true}")
    private val ledgerRebuildEnabled: Boolean = true,
    private val clock: Clock = Clock.System,
) : ExecutionStrategyPositionUpdatePort {
    private val log = LoggerFactory.getLogger(ConsumerExecutionStrategyPositionAdapter::class.java)

    @Transactional
    override fun applyExecution(
        request: OrderRequest,
        execution: OrderExecution,
    ) {
        // MANUAL sentinel(0L) 또는 미귀속 주문은 전략 lot ledger 대상 아님.
        if (request.strategyId == 0L) {
            log.debug("strategy_position upsert skip — MANUAL sentinel(strategyId=0): orderId={}", execution.orderId)
            return
        }
        val fillQty = execution.executedQuantity.coerceAtLeast(0)
        if (fillQty == 0) return

        val current =
            strategyPositionPort.findByUserStrategyCode(request.userId, request.strategyId, request.code)
        val now = clock.now()

        val next =
            when (request.side) {
                OrderSide.BUY -> applyBuy(request, execution, current, fillQty, now)
                OrderSide.SELL -> applySell(request, execution, current, fillQty, now)
            }
        strategyPositionPort.upsert(next)

        log.info(
            "strategy_position upsert: userId={} strategyId={} code={} side={} fillQty={} nextQty={} avgPrice={} filledLots={} realizedPnl={} exitStages={}",
            maskUserId(request.userId),
            request.strategyId,
            request.code,
            request.side,
            fillQty,
            next.quantity,
            next.avgPrice.toPlainString(),
            next.filledLots,
            next.realizedPnl.toPlainString(),
            next.executedExitStages,
        )

        // 체결 직후 경로 — postExecution=true. 이 시점 position(원장) 권위는 아직 이 체결을 반영하지 않았을
        // 수 있다(원장은 BalanceReconcile 주기에 뒤늦게 채워짐 — 실측 15:10 체결 vs 15:51 원장 반영). 따라서
        // "원장 0 + 방금 귀속한 leg 양수"를 초과로 깎으면 정상 물량을 즉시 파괴한다(아래 reconcileDrift 가드).
        reconcileDrift(request.userId, request.code, request.mode.name, postExecution = true)
    }

    /**
     * 외부(주기 reconcile worker) 진입점 — 체결 없이도 strategy_position drift 정합 (ADR 0115 §P1-1 amend 2026-06-10).
     *
     * 2026-06-10 incident: replay-skip 으로 [applyExecution] 경로가 우회되면 그 strategy_position 의 drift 가
     * **다음 체결 전까지 영구 잔존**한다(qty=0 vs KIS 3주 → 시그널 BLOCKED). 본 메서드를
     * [kr.spdaewoon.magicjar.consumer.reconcile.StrategyPositionReconcileWorker] 가 BalanceReconcile 주기에 호출해
     * 체결 이벤트와 무관하게 정합을 회복한다. 정정 로직은 체결 직후 경로와 동일([reconcileDrift]).
     *
     * 모드 무관(REAL/MOCK 동일 — position 권위 공통). DB 귀속 정렬만(주문 발행 0).
     */
    @Transactional
    fun reconcileStrategyPositionDrift(
        userId: String,
        code: String,
        modeName: String,
    ) {
        // 주기 reconcile 경로 — postExecution=false. position(원장)은 BalanceReconcile 가 이미 채운 뒤이므로
        // "원장 0 = 진짜 미보유"로 신뢰할 수 있다 → 초과(legs 양수 vs 권위 0) 정상 정정 대상.
        reconcileDrift(userId, code, modeName, postExecution = false)
    }

    /**
     * ★ strategy_position **rebuild** (ADR 0115 §2-P2 R1 — 대안 B-on-A 1급 메커니즘 · spec §3-3 · 2026-06-10).
     *
     * `order_execution ⨝ order_request`(진실원)에서 그 user+code 의 strategy_position 을 **전량 재계산**한다
     * ([StrategyPositionRebuild.rebuild] — 부분체결 N건 정확 N회 귀속 · eventId distinct 멱등 · over-count 구조적 0).
     * 어떤 경로로 누락돼도(replay-skip·backfill 우회·취소-체결 race) 본 rebuild 가 흡수 — 누락 자체가 영구화 불가(G2·G4 직격).
     *
     * ## 교차검산 게이트 (자본 경로 LOCK — KIS 권위 우선)
     * rebuild 산출 `totalQuantity`(전 전략 합)가 `position.quantity`(KIS 잔고 권위)와 **정확히 일치할 때만** upsert.
     * 불일치(외부 주문 혼재·진실원 자체 누락·over-sell clamp)면 **미적용 + WARN** — 함부로 덮어쓰지 않는다
     * (없는 물량 인식 risk 0). [RebuildResult.anyClampedNegativeSell] 도 진실원 정합 의심 신호로 미적용.
     *
     * ## 토글 (rollback)
     * `magicjar.position.ledger-rebuild.enabled=false` 면 no-op(현행 증분+drift 보정 경로로 회귀).
     *
     * 모드 무관(REAL/MOCK 동일 — position 권위 공통). DB 귀속 정렬만(주문 발행 0). realized_pnl 은 rebuild 가 진실원에서
     * 재계산하나(SELL 누적), 정상 흐름에서는 증분 결과와 1:1 정합하므로 손익 왜곡 0.
     *
     * @return 적용 시 true(교차검산 통과 + 변경 upsert), 미적용 시 false. caller(worker)가 index refresh 동반.
     */
    @Transactional
    fun rebuildStrategyPositionFromLedger(
        userId: String,
        code: String,
        modeName: String,
    ): Boolean {
        if (!ledgerRebuildEnabled) return false

        val authority =
            try {
                positionJpaRepository.findByUserIdAndCodeAndSource(userId, code, modeName)?.quantity ?: 0
            } catch (e: Exception) {
                log.warn("rebuild position 권위 조회 실패 — skip: userId={} code={} err={}", maskUserId(userId), code, e.message)
                return false
            }

        // hotfix 2026-06-11 (D1): rebuild fold·교차검산을 권위(position) 와 동일 mode 로 정합 — MOCK 과거 체결 혼입 차단.
        val mode = RuntimeModeParser.tradingModeOrMock(modeName)
        val rows =
            try {
                orderRequestJpaRepository.findRebuildExecutionsByUserAndCode(userId, code, mode)
            } catch (e: Exception) {
                log.warn("rebuild execution 조회 실패 — skip: userId={} code={} err={}", maskUserId(userId), code, e.message)
                return false
            }
        if (rows.isEmpty()) return false

        val executions =
            rows.map { row ->
                RebuildExecution(
                    eventId = row.eventId ?: "ROWID:${row.orderId}:${row.executedAt.toEpochMilli()}:${row.executedQty.toPlainString()}",
                    orderId = row.orderId,
                    strategyId = row.strategyId,
                    side = if (row.side == OrderSide.BUY) RebuildSide.BUY else RebuildSide.SELL,
                    quantity = row.executedQty.setScale(0, RoundingMode.DOWN).toInt(),
                    price = row.executedPrice,
                    partialExitStage = resolvePartialExitStage(row.side, row.signalId),
                    executedAtEpochMillis = row.executedAt.toEpochMilli(),
                )
            }

        val rebuilt = StrategyPositionRebuild.rebuild(RebuildInput(executions = executions))

        // 교차검산 — KIS 권위와 정확히 일치 + over-sell clamp 0 일 때만 적용(불일치 시 WARN+미적용).
        if (rebuilt.anyClampedNegativeSell || rebuilt.totalQuantity != authority) {
            log.warn(
                "STRATEGY_POSITION_REBUILD_SKIPPED(교차검산 불일치): userId={} code={} rebuildTotal={} authority={} clamped={} — 미적용(KIS 권위 우선)",
                maskUserId(userId),
                code,
                rebuilt.totalQuantity,
                authority,
                rebuilt.anyClampedNegativeSell,
            )
            return false
        }

        val existingByStrategy =
            try {
                strategyPositionPort.findByUserCode(userId, code).associateBy { it.strategyId }
            } catch (e: Exception) {
                log.warn("rebuild 기존 leg 조회 실패 — skip: userId={} code={} err={}", maskUserId(userId), code, e.message)
                return false
            }

        val now = clock.now()
        var applied = 0
        for ((strategyId, rebuiltPos) in rebuilt.positions) {
            val existing = existingByStrategy[strategyId]
            // 멱등 — 이미 동일 수량·평단이면 skip(over-write 0).
            if (existing != null &&
                existing.quantity == rebuiltPos.quantity &&
                existing.avgPrice.compareTo(rebuiltPos.avgPrice) == 0
            ) {
                continue
            }
            val target =
                StrategyPosition(
                    id = existing?.id,
                    userId = userId,
                    strategyId = strategyId,
                    code = code,
                    quantity = rebuiltPos.quantity,
                    avgPrice = rebuiltPos.avgPrice,
                    filledLots = rebuiltPos.filledLots,
                    realizedPnl = rebuiltPos.realizedPnl,
                    executedExitStages = rebuiltPos.executedExitStages,
                    openedAt = existing?.openedAt ?: now,
                    updatedAt = now,
                )
            try {
                strategyPositionPort.upsert(target)
                applied++
            } catch (e: Exception) {
                log.error(
                    "STRATEGY_POSITION_REBUILD upsert 실패: userId={} strategyId={} code={} err={}",
                    maskUserId(userId),
                    strategyId,
                    code,
                    e.message,
                )
            }
        }

        if (applied == 0) return false

        log.warn(
            "STRATEGY_POSITION_REBUILD: userId={} code={} authority={} legs={} applied={} (order_execution 진실원 재계산)",
            maskUserId(userId),
            code,
            authority,
            rebuilt.positions.size,
            applied,
        )
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_REBUILD",
            detailJson =
                """{"code":"$code","authority":$authority,"legs":${rebuilt.positions.size},"applied":$applied}""",
        )
        return true
    }

    /** SELL 체결의 시그널 reason `PARTIAL_TAKE_PROFIT#k` → 단계 k(rebuild fold 의 partialExitStage). BUY·sentinel·미매칭 null. */
    private fun resolvePartialExitStage(
        side: OrderSide,
        signalId: Long,
    ): Int? {
        if (side != OrderSide.SELL || signalId == 0L) return null
        val reason =
            try {
                signalJpaRepository.findById(signalId).orElse(null)?.reason
            } catch (e: Exception) {
                return null
            } ?: return null
        return PARTIAL_TP_REASON
            .find(reason)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun applyBuy(
        request: OrderRequest,
        execution: OrderExecution,
        current: StrategyPosition?,
        fillQty: Int,
        now: kotlinx.datetime.Instant,
    ): StrategyPosition {
        val currentQty = current?.quantity ?: 0
        val currentAvg = current?.avgPrice ?: BigDecimal.ZERO
        val nextQty = currentQty + fillQty
        val currentNotional = currentAvg.multiply(BigDecimal(currentQty))
        val fillNotional = execution.executedPrice.multiply(BigDecimal(fillQty))
        val nextAvg =
            currentNotional
                .add(fillNotional)
                .divide(BigDecimal(nextQty), 4, RoundingMode.HALF_UP)
        return baseOf(request, current, now).copy(
            quantity = nextQty,
            avgPrice = nextAvg,
            filledLots = (current?.filledLots ?: 0) + 1,
        )
    }

    private fun applySell(
        request: OrderRequest,
        execution: OrderExecution,
        current: StrategyPosition?,
        fillQty: Int,
        now: kotlinx.datetime.Instant,
    ): StrategyPosition {
        val currentQty = current?.quantity ?: 0
        val currentAvg = current?.avgPrice ?: BigDecimal.ZERO
        val sellQty = fillQty.coerceAtMost(currentQty)
        if (fillQty > currentQty) {
            log.warn(
                "strategy_position SELL overflow (KIS 권위 우선 — clamp): userId={} strategyId={} code={} currentQty={} fillQty={}",
                maskUserId(request.userId),
                request.strategyId,
                request.code,
                currentQty,
                fillQty,
            )
        }
        val realizedDelta =
            execution.executedPrice
                .subtract(currentAvg)
                .multiply(BigDecimal(sellQty))
        val nextQty = (currentQty - sellQty).coerceAtLeast(0)
        val nextRealized = (current?.realizedPnl ?: BigDecimal.ZERO).add(realizedDelta)

        // 체결 확정 시 PartialExit 단계 마킹 (발행 아닌 체결 시점 — spec §3-1).
        val markedStages = markPartialExitStage(request, current?.executedExitStages ?: emptySet())

        return if (nextQty == 0) {
            // 전량 청산 — avgPrice·filledLots·executedExitStages reset (행 유지, 재진입 새 사이클).
            baseOf(request, current, now).copy(
                quantity = 0,
                avgPrice = BigDecimal.ZERO,
                filledLots = 0,
                realizedPnl = nextRealized,
                executedExitStages = emptySet(),
            )
        } else {
            baseOf(request, current, now).copy(
                quantity = nextQty,
                avgPrice = currentAvg,
                realizedPnl = nextRealized,
                executedExitStages = markedStages,
            )
        }
    }

    /**
     * SELL 체결의 시그널 reason 이 `PARTIAL_TAKE_PROFIT#{k}` 면 단계 k 마킹(체결 시점).
     * signalId=0(sentinel) 또는 reason 미매칭이면 기존 집합 그대로.
     */
    private fun markPartialExitStage(
        request: OrderRequest,
        existing: Set<Int>,
    ): Set<Int> {
        if (request.signalId == 0L) return existing
        val reason =
            try {
                signalJpaRepository.findById(request.signalId).orElse(null)?.reason
            } catch (e: Exception) {
                log.warn("strategy_position partial-exit reason 조회 실패 — 단계 마킹 skip: signalId={} err={}", request.signalId, e.message)
                null
            } ?: return existing
        val stage =
            PARTIAL_TP_REASON
                .find(reason)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: return existing
        return existing + stage
    }

    private fun baseOf(
        request: OrderRequest,
        current: StrategyPosition?,
        now: kotlinx.datetime.Instant,
    ): StrategyPosition =
        StrategyPosition(
            id = current?.id,
            userId = request.userId,
            strategyId = request.strategyId,
            code = request.code,
            quantity = current?.quantity ?: 0,
            avgPrice = current?.avgPrice ?: BigDecimal.ZERO,
            filledLots = current?.filledLots ?: 0,
            realizedPnl = current?.realizedPnl ?: BigDecimal.ZERO,
            executedExitStages = current?.executedExitStages ?: emptySet(),
            openedAt = current?.openedAt ?: now,
            updatedAt = now,
        )

    /**
     * drift 정합 — `sum(strategy_position.quantity WHERE user+code)` 를 `position.quantity`(KIS 잔고 권위)에 정렬한다.
     *
     * ADR 0115 §P1-1 (R2 배선). R1 순수 함수 [PositionDriftCorrection.evaluate]가 보정 수량을 산정하고,
     * 본 메서드가 그 결과를 strategy_position DB 에 반영한다(수량만 — realized_pnl·avgPrice 불변 LOCK).
     *
     * - [DriftCorrectionResult.Adjust]: 초과 leg 를 권위 수량에 맞춰 내림 UPDATE + `STRATEGY_POSITION_DRIFT_CORRECTED`
     *   SystemEvent(WARN, before/after qty). 수량==0 도달 leg 는 avgPrice·filledLots·executedExitStages reset(applySell 정합).
     * - [DriftCorrectionResult.WarnUnderflow]: 부족 방향 — 보정하지 않고 현행 `STRATEGY_POSITION_DRIFT` WARN 유지(없는 물량 인식 회피).
     * - [DriftCorrectionResult.NoChange]/[DriftCorrectionResult.Disabled]: 무동작(Disabled 는 ENV off → 현행 WARN-only fallback).
     *
     * 모드 무관(REAL/MOCK 동일 — position 권위 공통, ADR 0115 §P1-1). 실주문 액션 게이트는 본 경로와 무관(수량 정렬만).
     *
     * ## 체결 직후 빈틈 보존 가드 (2026-06-15 — strategy_position drift 근본 fix)
     * 운영 실측(005930·전략 88, 15:10:01 매수 333주 체결인데 strategy_position 88 이 0 으로 잔존):
     *   applyExecution → applyBuy(+1) → reconcileDrift 즉시 호출 → **체결 직후라 position(원장) quantity 가 아직 0**
     *   (원장은 BalanceReconcile 주기에 뒤늦게 채워짐 — 실측 15:51 에야 1) → legs(1) > authority(0) = 초과 판정
     *   → Adjust 로 방금 더한 1주를 즉시 삭제.
     * 기존 가드(authorityRow==null)는 "행 부재"만 막아서, 행이 **존재하지만 quantity=0**(체결 직후) 인 경우는 뚫렸다.
     *
     * fix: [postExecution]=true(체결 직후 경로)면 "권위 0(원장 미반영 가능) + legs 양수" 도 초과 Adjust 보류 + WARN
     *   (다음 정합 주기에 회복 — 부족 방향 WARN-only 와 동일 철학으로 정상 물량 보존). 주기 reconcile
     *   ([reconcileStrategyPositionDrift], postExecution=false)는 원장이 이미 채워진 뒤이므로 권위 0=진짜 미보유로
     *   신뢰 → 정상 정정(미체결·미보유 케이스와 구분).
     */
    private fun reconcileDrift(
        userId: String,
        code: String,
        modeName: String,
        postExecution: Boolean,
    ) {
        val legs =
            try {
                strategyPositionPort.findByUserCode(userId, code)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position drift leg 조회 실패 — 정합 skip: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                return
            }
        val authorityRow =
            try {
                positionJpaRepository.findByUserIdAndCodeAndSource(userId, code, modeName)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position drift position 조회 실패 — 정합 skip: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                return
            }

        val positionQty = authorityRow?.quantity ?: 0
        val hasPositiveLeg = legs.any { it.quantity > 0 }

        // hotfix 2026-06-11 (D1): 이 source(REAL/MOCK) 의 position 행 자체가 **부재**면 "확정 0 보유"가 아니다.
        // 첫 REAL 체결 직후 권위 행이 아직 없거나, 잘못된 source 로 조회됐을 수 있다. 이때 authority=0 으로 간주해
        // 초과 내림(Adjust)을 적용하면 방금 정상 귀속된 leg 를 즉시 삭제한다(13:31:01.963 strategySum=1->0 라이브 결함).
        //
        // 2026-06-15 확장(strategy_position drift 근본 fix): 행 부재(authorityRow==null) 뿐 아니라, 행이
        // **존재하지만 quantity=0** 인 경우도 **체결 직후 경로(postExecution=true)** 에서는 "확정 0 보유"로 믿을 수 없다.
        // 원장은 BalanceReconcile 주기에 뒤늦게 채워지므로(005930·전략88 실측: 15:10 체결 vs 15:51 원장 반영),
        // 체결 직후 reconcile 은 권위 0 + 방금 귀속한 leg 양수를 초과로 깎으면 정상 물량을 즉시 파괴한다.
        // → 보정하지 않고 skip(다음 정합 주기에 회복). 주기 reconcile(postExecution=false)은 원장이 채워진 뒤이므로
        //   권위 0=진짜 미보유로 신뢰 → 가드 없이 정상 정정(미체결·미보유와 구분).
        val authorityUnreliableZero = authorityRow == null || (postExecution && positionQty == 0)
        if (authorityUnreliableZero && hasPositiveLeg) {
            log.warn(
                "strategy_position drift skip(권위 0 신뢰불가 — 확정 0 아님): userId={} code={} source={} " +
                    "authorityRowPresent={} positionQty={} postExecution={} legSum={} (다음 정합 주기 회복)",
                maskUserId(userId),
                code,
                modeName,
                authorityRow != null,
                positionQty,
                postExecution,
                legs.sumOf { it.quantity },
            )
            return
        }

        val result =
            PositionDriftCorrection.evaluate(
                DriftCorrectionInput(
                    code = code,
                    authorityQuantity = positionQty,
                    legs =
                        legs.map { leg ->
                            StrategyPositionLeg(
                                strategyId = leg.strategyId,
                                quantity = leg.quantity,
                                updatedAt = leg.updatedAt,
                            )
                        },
                    enabled = driftAutoCorrectEnabled,
                ),
            )

        when (result) {
            is DriftCorrectionResult.Adjust -> applyDriftCorrection(userId, code, legs, result)
            // 부족 방향 — 귀속 ledger 로 정정 가능한지 먼저 시도(ADR 0115 §P1-1 amend 2026-06-10).
            // 명확한 귀속(전략 FILLED execution)으로 authority 재구성 가능하면 정정, 모호하면 WARN.
            is DriftCorrectionResult.WarnUnderflow ->
                reconcileUnderflowByLedger(userId, code, modeName, legs, positionQty, result)
            // Disabled(ENV off) — 현행 WARN-only 로 fallback. 일치/정합이면 무동작.
            is DriftCorrectionResult.Disabled -> warnIfDisabledDrift(userId, code, positionQty)
            DriftCorrectionResult.NoChange -> Unit
        }
    }

    /**
     * 부족(strategySum < authority) 방향 drift — 귀속 ledger 기반 자동 정정 시도 (ADR 0115 §P1-1 amend, 2026-06-10).
     *
     * 2026-06-10 incident: WS 체결통보 유실→REST backfill 흡수된 FILLED execution 이 replay-skip 으로
     * strategy_position 에 미반영(qty=0 vs KIS 3주). 외부 주문이 아니라 **우리 전략이 체결시킨 주문의 ledger 미반영**이므로
     * order_request.strategy_id 라는 명확한 귀속 근거가 있다 → [LedgerAttributedDriftCorrection] 3중 게이트로 좁게 정정.
     *
     * - [DriftCorrectionResult.Adjust]: ledger 로 authority 정확히 재구성 → leg 수량·평단 정정 + `STRATEGY_POSITION_DRIFT_CORRECTED`.
     * - 그 외(모호 — ledger 합 ≠ authority): 현행 [emitDriftWarn] WARN-only (운영 알림·수동 판단).
     */
    private fun reconcileUnderflowByLedger(
        userId: String,
        code: String,
        modeName: String,
        legs: List<StrategyPosition>,
        positionQty: Int,
        underflow: DriftCorrectionResult.WarnUnderflow,
    ) {
        // hotfix 2026-06-11 (D1): 귀속 ledger 집계를 권위 mode 로 정합(MOCK 과거 체결 혼입 차단).
        val mode = RuntimeModeParser.tradingModeOrMock(modeName)
        val ledgerByStrategy =
            try {
                aggregateLedgerAttribution(userId, code, mode)
            } catch (e: Exception) {
                log.warn(
                    "strategy_position underflow ledger 집계 실패 — WARN-only fallback: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                emitDriftWarn(userId, code, underflow.strategySum, underflow.authorityQuantity)
                return
            }

        // ADR 0115 §2-P2-③ (G7 — 단일전략+evidence 자동보정) — execution 차원 ledger net 이 부분체결 누락으로
        //   부족하게 잡혀 1차 검산이 깨질 때, 주문 차원 FILLED 순합계 evidence 로 완화한다.
        //   보유 기여 전략이 정확히 1개일 때만 산출(다전략 혼재면 null → 현행 WARN-only 회귀 안전).
        val orderEvidence = computeSingleStrategyOrderEvidence(userId, code, mode, legs, ledgerByStrategy)

        val result =
            LedgerAttributedDriftCorrection.evaluate(
                LedgerDriftInput(
                    code = code,
                    authorityQuantity = positionQty,
                    legs =
                        legs.map { leg ->
                            StrategyPositionLeg(
                                strategyId = leg.strategyId,
                                quantity = leg.quantity,
                                updatedAt = leg.updatedAt,
                            )
                        },
                    attributions = ledgerByStrategy.values.map { it.attribution },
                    enabled = driftAutoCorrectEnabled,
                    orderEvidence = orderEvidence,
                ),
            )

        when (result) {
            is DriftCorrectionResult.Adjust ->
                applyLedgerUnderflowCorrection(userId, code, legs, ledgerByStrategy, result)
            // ledger 로 정정 불가(모호) — 현행 WARN-only.
            else -> emitDriftWarn(userId, code, underflow.strategySum, underflow.authorityQuantity)
        }
    }

    /**
     * user+code 의 전략별 귀속 ledger 집계 — (strategyId → net 순매수량 + BUY 가중평단).
     *
     * `aggregateFilledByStrategyAndSide`(strategyId,side)→(qtySum,notionalSum) 행을 전략별로 묶어
     * net = BUY qty - SELL qty, avgBuyPrice = BUY notional / BUY qty 산정. AUTO·strategyId≠0 만 집계(JPA WHERE).
     */
    private fun aggregateLedgerAttribution(
        userId: String,
        code: String,
        mode: kr.spdaewoon.magicjar.domain.order.execution.TradingMode,
    ): Map<Long, LedgerStrategyAggregate> {
        val rows = orderRequestJpaRepository.aggregateFilledByStrategyAndSide(userId, code, mode)
        val buyQty = mutableMapOf<Long, Int>()
        val sellQty = mutableMapOf<Long, Int>()
        val buyNotional = mutableMapOf<Long, BigDecimal>()
        for (row in rows) {
            val qty = row.qtySum.setScale(0, RoundingMode.DOWN).toInt()
            when (row.side) {
                OrderSide.BUY -> {
                    buyQty[row.strategyId] = (buyQty[row.strategyId] ?: 0) + qty
                    buyNotional[row.strategyId] =
                        (buyNotional[row.strategyId] ?: BigDecimal.ZERO).add(row.notionalSum)
                }
                OrderSide.SELL -> sellQty[row.strategyId] = (sellQty[row.strategyId] ?: 0) + qty
            }
        }
        val strategyIds = buyQty.keys + sellQty.keys
        return strategyIds.associateWith { strategyId ->
            val bq = buyQty[strategyId] ?: 0
            val sq = sellQty[strategyId] ?: 0
            val net = bq - sq
            val avgBuy =
                if (bq > 0) {
                    (buyNotional[strategyId] ?: BigDecimal.ZERO).divide(BigDecimal(bq), 4, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }
            LedgerStrategyAggregate(
                attribution = LedgerAttribution(strategyId = strategyId, netFilledQuantity = net),
                avgBuyPrice = avgBuy,
            )
        }
    }

    /**
     * 단일 전략 부족 완화 evidence 산출 (ADR 0115 §2-P2-③, 2026-06-10).
     *
     * 보유에 기여하는 후보 전략(비-zero leg ∪ ledger net>0)이 **정확히 1개**일 때만, 그 전략의 **주문 차원**
     * FILLED 순매수량([ConsumerOrderRequestJpaRepository.aggregateOrderFilledNetByStrategy])을 evidence 로 산출한다.
     *
     * - 다전략 혼재(후보 ≥2) → null (귀속 모호 — 현행 WARN-only 회귀 안전).
     * - 후보 0 (전부 외부·MANUAL) → null (귀속 근거 0).
     * - 주문 차원 집계 조회 실패 → null (graceful — evidence 없이 ledger net 1차 검산만).
     *
     * evidence net 이 authority 와 정합하는지는 [LedgerAttributedDriftCorrection] 게이트가 최종 판정한다(본 함수는 산출만).
     */
    private fun computeSingleStrategyOrderEvidence(
        userId: String,
        code: String,
        mode: kr.spdaewoon.magicjar.domain.order.execution.TradingMode,
        legs: List<StrategyPosition>,
        ledgerByStrategy: Map<Long, LedgerStrategyAggregate>,
    ): SingleStrategyOrderEvidence? {
        val candidateStrategies =
            buildSet {
                legs.filter { it.quantity > 0 }.forEach { add(it.strategyId) }
                ledgerByStrategy.values
                    .filter { it.attribution.netFilledQuantity > 0 }
                    .forEach { add(it.attribution.strategyId) }
            }
        val singleStrategy = candidateStrategies.singleOrNull() ?: return null

        val orderNetByStrategy =
            try {
                aggregateOrderFilledNet(userId, code, mode)
            } catch (e: Exception) {
                log.warn(
                    "single-strategy evidence 주문차원 집계 실패 — evidence 없이 진행: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
                return null
            }
        val net = orderNetByStrategy[singleStrategy] ?: return null
        if (net <= 0) return null
        return SingleStrategyOrderEvidence(strategyId = singleStrategy, netFilledFromOrders = net)
    }

    /**
     * user+code 의 전략별 **주문 차원** FILLED 순매수량 (strategyId → net BUY-SELL).
     *
     * [aggregateLedgerAttribution](execution 차원)과 동일 형상이나 status FILLED·PARTIALLY_FILLED 종결 주문 한정 —
     * execution-단위 누락이 있어도 KIS 권위로 종결된 주문의 체결 의도를 evidence 로 보존한다.
     */
    private fun aggregateOrderFilledNet(
        userId: String,
        code: String,
        mode: kr.spdaewoon.magicjar.domain.order.execution.TradingMode,
    ): Map<Long, Int> {
        val rows = orderRequestJpaRepository.aggregateOrderFilledNetByStrategy(userId, code, mode)
        val buyQty = mutableMapOf<Long, Int>()
        val sellQty = mutableMapOf<Long, Int>()
        for (row in rows) {
            val qty = row.qtySum.setScale(0, RoundingMode.DOWN).toInt()
            when (row.side) {
                OrderSide.BUY -> buyQty[row.strategyId] = (buyQty[row.strategyId] ?: 0) + qty
                OrderSide.SELL -> sellQty[row.strategyId] = (sellQty[row.strategyId] ?: 0) + qty
            }
        }
        return (buyQty.keys + sellQty.keys).associateWith { (buyQty[it] ?: 0) - (sellQty[it] ?: 0) }
    }

    /**
     * 부족 보정 적용 — leg 수량을 ledger 순매수량으로 set + 평단 복원(ledger BUY 가중평단) + 감사.
     *
     * incident 경로: 기존 leg(qty=0·avgPrice=0 reset)를 ledger net(예: 3)·avgBuy 로 복원. realized_pnl 보존(체결만 변동).
     * leg 부재(신규 전략) 시 새 row 생성. over-count 0 — newQuantity 는 절대 목표(멱등).
     */
    private fun applyLedgerUnderflowCorrection(
        userId: String,
        code: String,
        legs: List<StrategyPosition>,
        ledgerByStrategy: Map<Long, LedgerStrategyAggregate>,
        result: DriftCorrectionResult.Adjust,
    ) {
        val legById = legs.associateBy { it.strategyId }
        val now = clock.now()
        for (correction in result.corrections) {
            val avgBuy = ledgerByStrategy[correction.strategyId]?.avgBuyPrice ?: BigDecimal.ZERO
            val existing = legById[correction.strategyId]
            val corrected =
                if (existing != null) {
                    // 평단은 ledger BUY 가중평단으로 복원(reset 된 0 → 실제 진입가). realized_pnl·openedAt 보존.
                    existing.copy(
                        quantity = correction.newQuantity,
                        avgPrice = avgBuy,
                        updatedAt = now,
                    )
                } else {
                    // leg 부재(귀속 전략의 row 자체 없음) — 신규 생성. realized_pnl 0(체결 손익은 청산 시 누적).
                    StrategyPosition(
                        id = null,
                        userId = userId,
                        strategyId = correction.strategyId,
                        code = code,
                        quantity = correction.newQuantity,
                        avgPrice = avgBuy,
                        filledLots = 0,
                        realizedPnl = BigDecimal.ZERO,
                        executedExitStages = emptySet(),
                        openedAt = now,
                        updatedAt = now,
                    )
                }
            try {
                strategyPositionPort.upsert(corrected)
            } catch (e: Exception) {
                log.error(
                    "STRATEGY_POSITION_DRIFT_CORRECTED(underflow) upsert 실패: userId={} strategyId={} code={} err={}",
                    maskUserId(userId),
                    correction.strategyId,
                    code,
                    e.message,
                )
            }
        }
        log.warn(
            "STRATEGY_POSITION_DRIFT_CORRECTED(underflow): userId={} code={} strategySum={}->{} corrections={} (귀속 ledger 정정)",
            maskUserId(userId),
            code,
            result.strategySum,
            result.authorityQuantity,
            result.corrections.joinToString { "${it.strategyId}:${it.previousQuantity}->${it.newQuantity}" },
        )
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_DRIFT_CORRECTED",
            detailJson =
                """{"code":"$code","direction":"underflow","strategySum":${result.strategySum},""" +
                    """"authorityQuantity":${result.authorityQuantity},""" +
                    """"corrections":[${result.corrections.joinToString(",") {
                        """{"strategyId":${it.strategyId},"prev":${it.previousQuantity},"next":${it.newQuantity}}"""
                    }}]}""",
        )
    }

    /**
     * [DriftCorrectionResult.Adjust] 적용 — 보정 leg 별 strategy_position 수량 UPDATE + 감사 이벤트(디바운스 1건).
     * realized_pnl 절대 미변경. quantity==0 도달 leg 는 전량 청산 reset(applySell nextQty==0 분기 정합).
     */
    private fun applyDriftCorrection(
        userId: String,
        code: String,
        legs: List<StrategyPosition>,
        result: DriftCorrectionResult.Adjust,
    ) {
        val legById = legs.associateBy { it.strategyId }
        val now = clock.now()
        for (correction in result.corrections) {
            val leg = legById[correction.strategyId] ?: continue
            val corrected =
                if (correction.willBeClosed) {
                    leg.copy(
                        quantity = 0,
                        avgPrice = BigDecimal.ZERO,
                        filledLots = 0,
                        executedExitStages = emptySet(),
                        updatedAt = now,
                    )
                } else {
                    leg.copy(quantity = correction.newQuantity, updatedAt = now)
                }
            try {
                strategyPositionPort.upsert(corrected)
            } catch (e: Exception) {
                log.error(
                    "STRATEGY_POSITION_DRIFT_CORRECTED upsert 실패: userId={} strategyId={} code={} err={}",
                    maskUserId(userId),
                    correction.strategyId,
                    code,
                    e.message,
                )
            }
        }
        log.warn(
            "STRATEGY_POSITION_DRIFT_CORRECTED: userId={} code={} strategySum={}->{} corrections={} (position 권위 정렬)",
            maskUserId(userId),
            code,
            result.strategySum,
            result.authorityQuantity,
            result.corrections.joinToString { "${it.strategyId}:${it.previousQuantity}->${it.newQuantity}" },
        )
        // 보정 1건당 감사 이벤트 1건(디바운스 — 보정은 정합 회복 즉시 1회만 발생).
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_DRIFT_CORRECTED",
            detailJson =
                """{"code":"$code","strategySum":${result.strategySum},"authorityQuantity":${result.authorityQuantity},""" +
                    """"corrections":[${result.corrections.joinToString(",") {
                        """{"strategyId":${it.strategyId},"prev":${it.previousQuantity},"next":${it.newQuantity}}"""
                    }}]}""",
        )
    }

    /** 부족 방향 drift — 귀속 ledger 로도 정정 불가(모호)할 때 현행 WARN 유지(보정 안 함). */
    private fun emitDriftWarn(
        userId: String,
        code: String,
        strategySum: Int,
        positionQty: Int,
    ) {
        log.warn(
            "STRATEGY_POSITION_DRIFT: userId={} code={} strategySum={} positionQty={} (부족 방향 — 귀속 근거 모호, position 권위 우선, 보정 안 함)",
            maskUserId(userId),
            code,
            strategySum,
            positionQty,
        )
        emitSystemEvent(
            userId = userId,
            action = "STRATEGY_POSITION_DRIFT",
            detailJson = """{"code":"$code","strategySum":$strategySum,"positionQty":$positionQty}""",
        )
    }

    /** ENV 보정 off(Disabled) 시 — 정합 차이가 있으면 현행 WARN-only fallback(보정 안 함). */
    private fun warnIfDisabledDrift(
        userId: String,
        code: String,
        positionQty: Int,
    ) {
        val strategySum =
            try {
                strategyPositionJpaRepository.sumQuantityByUserAndCode(userId, code)
            } catch (e: Exception) {
                return
            }
        if (strategySum != positionQty) {
            emitDriftWarn(userId, code, strategySum, positionQty)
        }
    }

    private fun emitSystemEvent(
        userId: String,
        action: String,
        detailJson: String,
    ) {
        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.RECONCILE,
                        severity = SystemEventSeverity.WARN,
                        actor = SYSTEM_EVENT_ACTOR,
                        action = action,
                        userId = userId,
                        detailJson = detailJson,
                    ),
                )
            }.onFailure { e ->
                log.error("SystemEventLogger.emit 실패: action={} err={}", action, e.message)
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /** 전략 1개의 귀속 ledger 집계 — 순매수량(보정 산정) + BUY 가중평단(부족 보정 시 평단 복원). */
    private data class LedgerStrategyAggregate(
        val attribution: LedgerAttribution,
        val avgBuyPrice: BigDecimal,
    )

    private companion object {
        const val SYSTEM_EVENT_ACTOR = "ConsumerExecutionStrategyPositionAdapter"
        val PARTIAL_TP_REASON = Regex("PARTIAL_TAKE_PROFIT#(\\d+)")
    }
}
