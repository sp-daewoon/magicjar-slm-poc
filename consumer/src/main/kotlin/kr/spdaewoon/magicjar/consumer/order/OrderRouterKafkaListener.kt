package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.consumer.account.AccountSnapshotCoordinator
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshot
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshotPort
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderIdempotencyGuard
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.TradingModeGate
import kr.spdaewoon.magicjar.domain.risk.port.TradeFrequencyGuard
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * `order.intent.v1` Kafka listener — T-O 진입점 (ADR 0068 §14-2 LOCK).
 *
 * ### 12 step 흐름 (§14-2 본문 인용)
 * ```
 * 1. payload deserialize → OrderIntent
 * 2. OrderIdempotencyGuard.acquire (Redis SETNX 60s, key="idem:order:{signalId}:{userId}")
 * 3. TradingModeGate.resolveMode(userId, paperStrategy) — 3중 게이트 + 페이퍼 MOCK 강제 (ADR 0122 §6 GAP-0 hotfix)
 * 4. KillSwitch 재검증
 * 5. REAL이면 KIS 당일 체결조회 + DB backfill 완결성 확인
 * 6. order_request INSERT(status=REQUESTED, mode)
 * 7. STOMP notifyRequested + Kafka order.requested.v1 publish
 * 8. KisOrderHttpAdapter.submit(intent, mode) — 5xx 1회 retry
 * 9. order_request UPDATE(status=ACK, kis_order_no, kis_fwdg_orgno, acknowledged_at)
 * 10. audit KIS_ACK
 * 11. (FILLED 통보는 KisExecutionWsListener)
 * 12. Kafka commit (MANUAL_IMMEDIATE)
 * ```
 *
 * ### Kafka 설정 LOCK (§14-2 자본 보호)
 * - enable.auto.commit=false (containerFactory에서 ack-mode=MANUAL_IMMEDIATE)
 * - max.poll.records=1 (분당 burst 회피)
 * - concurrency=1 per partition (멀티스레드 race 회피)
 * - isolation.level=read_committed
 *
 * 본 listener는 `manualImmediateKafkaListenerContainerFactory`를 명시해 KIS submit 이후 offset commit 시점을 직접 제어한다.
 *
 * ### Phase 2 graceful fallback (헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * OrderRequestRepository / OrderAuditEventRepository / KillSwitchQueryPort 미합류 시 ObjectProvider로
 * graceful skip + log.warn — 자본 변동 path이므로 미합류 시점 명확히 알림.
 *
 * ### ADR 0082 T-MAN B-2-extended — source 필드 처리 (Phase 1c)
 * - Step 1 deserialize: payload `source` 필드 추출 → `OrderSource.valueOf()` (default `AUTO`, backward compat)
 * - Step 5 INSERT: `OrderRequest.source = intent.source` 영속 (Flyway V37 컬럼)
 * - audit_event payloadJson: `"source"="AUTO"|"MANUAL"` 마커 (DB 컬럼 + JSON 이중 정합)
 * - 로깅: AUTO/MANUAL 분기를 log.info 명시 — 운영자 audit (Step 1 직후·ACK·REJECTED 모두)
 * - 비정상 `source` 값(예: `"FOO"`)은 `valueOf` IllegalArgumentException → outer try/catch → poison pill skip
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082 §8)
 */
@Component
class OrderRouterKafkaListener(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val killSwitchQueryPortProvider: ObjectProvider<KillSwitchQueryPort>,
    private val tradingModeGate: TradingModeGate,
    private val orderIdempotencyGuard: OrderIdempotencyGuard,
    private val kisOrderPort: KisOrderPort,
    private val orderRequestedKafkaProducer: OrderRequestedKafkaProducer,
    private val orderRejectedKafkaProducer: OrderRejectedKafkaProducer,
    private val accountBalanceChangedKafkaProducer: AccountBalanceChangedKafkaProducer,
    private val executionBackfillServiceProvider: ObjectProvider<KisExecutionBackfillService>,
    private val killSwitchJpaRepositoryProvider: ObjectProvider<KillSwitchJpaRepository>,
    private val marketSessionResolver: MarketSessionResolver,
    private val businessDayCalendarProvider: ObjectProvider<BusinessDayCalendar>,
    private val objectMapper: ObjectMapper,
    // ADR 0114 §4-3 P3 — AUTO BUY 제출 시 PENDING_BUY watch(체결 통보 구독 보장). ObjectProvider — graceful(부재 시 skip).
    private val holdingDemandRegistrarProvider: ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.HoldingDemandRegistrar>,
    // ADR 0115 §P2-2 — 같은 전략·종목 SELL 거부 N회 누적 시 자동 정지 + 1회 알림(유령 매도 폭주 차단).
    private val sellRejectAutoPauseTracker: SellRejectAutoPauseTracker,
    // WS_PREINSERT race 병합 시 누락된 strategy_position 재생. 부재 환경은 기존 주문 흐름 유지.
    private val strategyPositionExecutionReplayServiceProvider: ObjectProvider<StrategyPositionExecutionReplayService>? = null,
    // signal.generated.v1 단계에서 주문 intent 발행 직후 누적한 거래빈도 record 를 gate reject 시 회수한다.
    private val tradeFrequencyGuardProvider: ObjectProvider<TradeFrequencyGuard>? = null,
    // ADR 0122 §6 GAP-0 hotfix — intent.strategyId → strategy.origin 조회(페이퍼 MOCK 강제 판정).
    // ObjectProvider graceful — 부재 시 REAL fail-closed / MOCK ERROR 로그 + 진행 (헌법 §11-2).
    private val consumerStrategyJpaRepositoryProvider: ObjectProvider<ConsumerStrategyJpaRepository>? = null,
    // v3.13.1 — 주문 hot path 는 계좌 availability Redis snapshot read 만 수행. KIS REST 호출은 refresh worker 전용.
    private val accountAvailabilitySnapshotPortProvider: ObjectProvider<AccountAvailabilitySnapshotPort>? = null,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    @Value("\${magicjar.signal.market-hours-gate.enabled:true}")
    private val marketHoursGateEnabled: Boolean = true,
    // ADR 0109 — REAL canary 전용 hard cap (fail-open: 0 또는 미설정 = 비활성).
    // ENV: MAGICJAR_CANARY_MAX_ORDER_QTY / _MAX_ORDER_NOTIONAL_KRW / _MAX_ORDERS_PER_DAY.
    @Value("\${magicjar.canary.max-order-qty:0}")
    private val canaryMaxOrderQty: Int = 0,
    @Value("\${magicjar.canary.max-order-notional-krw:0}")
    private val canaryMaxOrderNotionalKrw: Long = 0,
    @Value("\${magicjar.canary.max-orders-per-day:0}")
    private val canaryMaxOrdersPerDay: Int = 0,
    @Value("\${magicjar.kis.account-availability.snapshot-guard-enabled:false}")
    private val accountAvailabilitySnapshotGuardEnabled: Boolean = false,
    @Value("\${magicjar.kis.account-availability.reject-on-insufficient:false}")
    private val accountAvailabilityRejectOnInsufficient: Boolean = false,
    @Value("\${magicjar.kis.account-availability.max-age-seconds:300}")
    private val accountAvailabilityMaxAgeSeconds: Long = 300,
) {
    private val log = LoggerFactory.getLogger(OrderRouterKafkaListener::class.java)

    private val kstZone = TimeZone.of("Asia/Seoul")

    companion object {
        const val TOPIC = KafkaTopics.Order.INTENT
        const val GROUP = "magicjar-consumer-order"
        private const val RETRY_BACKOFF_MS = 500L
        private const val POST_KIS_DB_SYNC_FAILED_REASON = "ORDER_DB_SYNC_FAILED"
    }

    /**
     * Kafka listen — partition key = userId (ADR 0067 §7-1 OrderIntentKafkaProducer 정합).
     * groupId = `magicjar-consumer-order` 독립 thread + offset.
     */
    @KafkaListener(
        topics = [TOPIC],
        groupId = GROUP,
        containerFactory = "manualImmediateKafkaListenerContainerFactory",
    )
    fun onOrderIntent(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            // Step 1: payload deserialize
            val intent = deserialize(record.value())
            if (intent == null) {
                log.error(
                    "OrderRouterKafkaListener deserialize 실패 (poison pill skip): offset={} key={} value={}",
                    record.offset(),
                    record.key(),
                    record.value()?.take(300),
                )
                ack.acknowledge() // poison pill 회피 — DLQ는 backlog
                return
            }

            // AUTO/MANUAL 분기 로깅 (ADR 0082 T-MAN B-2-extended — 운영자 audit)
            log.info(
                "OrderRouterKafkaListener OrderIntent source={} userId={} signalId={} strategyId={} code={} side={}",
                intent.source,
                intent.userId,
                intent.signalId,
                intent.strategyId,
                intent.code,
                intent.side,
            )

            // Step 2: 멱등성 가드
            val idemKey = "idem:order:${intent.idempotencyKey}"
            val acquired =
                try {
                    orderIdempotencyGuard.acquire(idemKey, 60)
                } catch (e: Exception) {
                    val reason = "IDEMPOTENCY_REDIS_DOWN"
                    writeAudit(
                        orderId = null,
                        userId = intent.userId,
                        eventType = OrderAuditEventType.REDIS_DOWN_FALLBACK,
                        payload =
                            mapOf(
                                "idempotencyKey" to intent.idempotencyKey,
                                "signalId" to intent.signalId,
                                "source" to intent.source.name,
                                "reason" to reason,
                                "error" to (e.message ?: "unknown"),
                            ),
                    )
                    handleRejected(
                        intent,
                        RuntimeModeParser.tradingModeOrMock(magicjarMode),
                        reason,
                        OrderAuditEventType.REDIS_DOWN_FALLBACK,
                    )
                    ack.acknowledge()
                    return
                }
            if (!acquired) {
                writeAudit(
                    orderId = null,
                    userId = intent.userId,
                    eventType = OrderAuditEventType.IDEMPOTENCY_SKIPPED,
                    // ADR 0082 T-MAN B-2-extended — DB 컬럼 + JSON 마커 이중 정합 (audit trail)
                    payload =
                        mapOf(
                            "idempotencyKey" to intent.idempotencyKey,
                            "signalId" to intent.signalId,
                            "source" to intent.source.name,
                        ),
                )
                log.warn(
                    "OrderRouterKafkaListener IDEMPOTENCY_SKIPPED: userId={} signalId={} key={}",
                    intent.userId,
                    intent.signalId,
                    intent.idempotencyKey,
                )
                ack.acknowledge()
                return
            }

            // Step 3: TradingModeGate (3중 게이트 + ADR 0122 §6 페이퍼 MOCK 강제 — GAP-0 hotfix)
            val paperResult = resolvePaperStrategy(intent)
            if (paperResult is OrderResult.Left) {
                val reason = "REAL_GATE_DENIED:${paperResult.value.condition}"
                handleRejected(intent, TradingMode.REAL, reason, OrderAuditEventType.REAL_GATE_DENIED)
                ack.acknowledge()
                return
            }
            val paperStrategy = (paperResult as OrderResult.Right).value
            val modeResult = tradingModeGate.resolveMode(intent.userId, paperStrategy)
            if (modeResult is OrderResult.Left) {
                val reason = "REAL_GATE_DENIED:${modeResult.value.condition}"
                handleRejected(intent, TradingMode.REAL, reason, OrderAuditEventType.REAL_GATE_DENIED)
                ack.acknowledge()
                return
            }
            val mode = (modeResult as OrderResult.Right).value

            if (intent.quantity <= 0) {
                handleRejected(intent, mode, "INVALID_ORDER_QUANTITY:${intent.quantity}", OrderAuditEventType.INVALID_ORDER_INTENT)
                ack.acknowledge()
                return
            }

            // Step 4: KillSwitch 재검증 (ADR 0024·0043)
            val killSwitchPort = killSwitchQueryPortProvider.getIfAvailable()
            val killSwitchOn =
                killSwitchPort?.let {
                    try {
                        it.isOn(intent.userId)
                    } catch (e: Exception) {
                        log.error("KillSwitchQueryPort.isOn 실패 (fail-closed): userId={} err={}", intent.userId, e.message)
                        true
                    }
                } ?: run {
                    log.error("KillSwitchQueryPort 미합류 (fail-closed): userId={} signalId={}", intent.userId, intent.signalId)
                    true
                }

            if (killSwitchOn) {
                handleRejected(intent, mode, "KILLSWITCH_ON", OrderAuditEventType.KILLSWITCH_DENIED)
                ack.acknowledge()
                return
            }

            // 장운영시간 게이트 — 최후 방어선 (2026-06-04 dogfooding 결함 fix).
            // AUTO 주문만 검사 — 현재 시각이 영업일+정규장 밖이면 KIS submit 전 REJECTED.
            // MANUAL 주문은 게이트 제외 (사용자 의도 존중 — KIS가 자체 거부 메시지 반환).
            // 장전 시그널 발화로 발생한 AUTO order.intent.v1 을 KIS 요청 직전에 차단 (defense in depth 2층).
            if (marketHoursGateEnabled &&
                intent.source == OrderSource.AUTO &&
                !isWithinTradingHours(Instant.now())
            ) {
                handleRejected(
                    intent,
                    mode,
                    "MARKET_CLOSED:NOT_REGULAR_SESSION_OR_HOLIDAY",
                    OrderAuditEventType.INVALID_ORDER_INTENT,
                )
                ack.acknowledge()
                return
            }

            val syncResult = ensureKisExecutionSync(intent, mode)
            if (!syncResult.allowed) {
                handleRejected(intent, mode, syncResult.reason, OrderAuditEventType.KIS_EXECUTION_SYNC_STALE)
                ack.acknowledge()
                return
            }

            // ADR 0109 — canary 전역 hard cap (3중게이트·KillSwitch·장운영시간·KIS sync 통과 후, INSERT 이전).
            // REAL 모드 + ENV>0 일 때만 활성 (fail-open). RiskConfig·전략 설정과 독립된 마지막 자본 안전선.
            val canaryDeny = evaluateCanaryHardCap(intent, mode)
            if (canaryDeny != null) {
                handleRejected(intent, mode, canaryDeny, OrderAuditEventType.INVALID_ORDER_INTENT)
                ack.acknowledge()
                return
            }

            val availabilityDeny = evaluateAccountAvailabilitySnapshot(intent, mode)
            if (availabilityDeny != null) {
                handleRejected(intent, mode, availabilityDeny, OrderAuditEventType.INVALID_ORDER_INTENT)
                ack.acknowledge()
                return
            }

            // Step 5: order_request INSERT(status=REQUESTED)
            val saved =
                when (val result = persistRequested(intent, mode)) {
                    is PersistResult.NotWired -> {
                        // Repository 미합류 — Phase 2 미완. 자본 path이므로 listener 진행 중단 + log.error.
                        log.error(
                            "OrderRouterKafkaListener — OrderRequestRepository 미합류 (Phase 2 미완): userId={} signalId={}",
                            intent.userId,
                            intent.signalId,
                        )
                        ack.acknowledge()
                        return
                    }
                    is PersistResult.SaveFailed -> {
                        // save 자체는 시도됐으나 DB 단에서 실패 (FK violation·연결 끊김 등). root cause 명시.
                        log.error(
                            "OrderRouterKafkaListener — OrderRequestRepository.save 실패 (listener 진행 중단): userId={} signalId={} reason={}",
                            intent.userId,
                            intent.signalId,
                            result.reason,
                        )
                        ack.acknowledge()
                        return
                    }
                    is PersistResult.Ok -> result.saved
                }

            // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드: 자본 path에서 saved.id null은 invariant 위반.
            // JPA save 후 generated id 미설정 시 명시 throw + audit 메시지로 운영자 즉시 매칭 가능.
            val savedOrderId =
                requireNotNull(saved.id) {
                    "OrderRouterKafkaListener — saved.id null after JPA save (자본 path invariant 위반): " +
                        "userId=${intent.userId} signalId=${intent.signalId}"
                }

            // Step 6: Kafka order.requested.v1 publish (STOMP notify는 Phase 2 backend-core가 listen)
            orderRequestedKafkaProducer.publish(saved)
            invalidateAccountSnapshot(saved, reason = "ORDER_REQUESTED")

            // Step 7: KIS REST submit (5xx 1회 retry)
            val kisResult =
                submitWithRetry(intent, mode)
            if (kisResult is OrderResult.Left) {
                val deny = kisResult.value
                val rejectReason =
                    when (deny) {
                        is DenyReason.KisRejected -> "${deny.rtCd}:${deny.msg}"
                        is DenyReason.KisException -> "KIS_EXCEPTION:${deny.cause}"
                        is DenyReason.KisTimeoutPendingReconcile -> "KIS_RECONCILE_PENDING:${deny.cause}"
                        is DenyReason.RealGateDenied -> "REAL_GATE_DENIED:${deny.condition}"
                        DenyReason.IdempotencyDuplicate -> "IDEMPOTENCY_DUPLICATE"
                        DenyReason.KillSwitchOn -> "KILLSWITCH_ON"
                    }

                // ADR 0084 P0-2 — KisTimeoutPendingReconcile은 REJECTED 대신 PENDING_RECONCILE 분기.
                // KIS는 주문을 실제 접수했을 수 있어 ambiguous → OrderReconciliationWorker가 검증.
                val isPendingReconcile = deny is DenyReason.KisTimeoutPendingReconcile
                val targetStatus = if (isPendingReconcile) OrderStatus.PENDING_RECONCILE else OrderStatus.REJECTED
                val now = Instant.now()

                val transitioned =
                    try {
                        val updated =
                            if (isPendingReconcile) {
                                // PENDING_RECONCILE 상태로만 마킹 — completedAt 미설정 (worker 결정 후 설정).
                                orderRequestRepositoryProvider
                                    .getIfAvailable()
                                    ?.updateStatus(savedOrderId, OrderStatus.PENDING_RECONCILE, rejectReason)
                            } else {
                                orderRequestRepositoryProvider
                                    .getIfAvailable()
                                    ?.updateStatus(savedOrderId, OrderStatus.REJECTED, rejectReason)
                            }
                        updated ?: throw IllegalStateException("OrderRequestRepository.updateStatus returned null")
                    } catch (e: Exception) {
                        log.error("updateStatus $targetStatus 실패: orderId={} err={}", saved.id, e.message)
                        handlePostKisDbSyncFailure(
                            userId = saved.userId,
                            orderId = savedOrderId,
                            mode = mode,
                            stage = "UPDATE_STATUS_$targetStatus",
                            cause = e,
                        )
                        saved.copy(
                            status = targetStatus,
                            rejectReason = rejectReason,
                            completedAt = if (isPendingReconcile) null else now,
                        )
                    }

                val auditType =
                    when (deny) {
                        is DenyReason.KisRejected -> OrderAuditEventType.KIS_REJECT
                        is DenyReason.KisException -> OrderAuditEventType.KIS_EXCEPTION
                        is DenyReason.KisTimeoutPendingReconcile -> OrderAuditEventType.KIS_RECONCILE_PENDING
                        else -> OrderAuditEventType.KIS_REJECT
                    }
                writeAudit(
                    orderId = saved.id,
                    userId = saved.userId,
                    eventType = auditType,
                    // ADR 0082 T-MAN B-2-extended — DB 컬럼 + JSON 마커 이중 정합
                    payload = mapOf("reason" to rejectReason, "source" to intent.source.name),
                )
                invalidateAccountSnapshot(transitioned, reason = targetStatus.name, kisOrderNo = transitioned.kisOrderNo)

                if (isPendingReconcile) {
                    // PENDING_RECONCILE은 Kafka order.rejected.v1 미발행 — REJECTED 확정 전.
                    // 운영자 가시성 보강을 위한 STOMP 별도 알림은 P1 (외부 코드 리뷰 의제 #8 STOMP 영속) 영역.
                    log.warn(
                        "OrderRouterKafkaListener PENDING_RECONCILE 보류 (자본 audit 보호): userId={} signalId={} reason={} orderId={}",
                        intent.userId,
                        intent.signalId,
                        rejectReason,
                        saved.id,
                    )
                } else {
                    orderRejectedKafkaProducer.publish(transitioned, rejectReason)
                    // ADR 0115 §P2-2 — SELL 거부 누적 추적(임계 도달 시 전략 자동 정지 + 1회 알림).
                    sellRejectAutoPauseTracker.onOrderRejected(transitioned)
                    // Polling → STOMP push (2026-05-20) — REJECTED 시 예약 cash 환원 가능 → 잔고 invalidate trigger.
                    accountBalanceChangedKafkaProducer.publish(
                        userId = transitioned.userId,
                        mode = transitioned.mode,
                        reason = AccountBalanceChangedKafkaProducer.REASON_REJECTED,
                        triggerKisOrderNo = transitioned.kisOrderNo,
                    )
                }
                ack.acknowledge()
                return
            }

            // Step 8·9: ACK 처리
            val ackData = (kisResult as OrderResult.Right).value
            val ackTime = Instant.now()
            val orderRepoForAck = orderRequestRepositoryProvider.getIfAvailable()
            val wsPreinsert = findWsPreinsertBeforeAck(orderRepoForAck, ackData.kisOrderNo)
            try {
                val updated =
                    orderRepoForAck
                        ?.updateKisAck(savedOrderId, ackData.kisOrderNo, ackData.kisFwdgOrgno, ackTime)
                if (updated == null) {
                    throw IllegalStateException("OrderRequestRepository.updateKisAck returned null")
                }
                replayStrategyPositionAfterWsPreinsertMerge(wsPreinsert, updated)
            } catch (e: Exception) {
                log.error("updateKisAck 실패: orderId={} err={}", savedOrderId, e.message)
                handlePostKisDbSyncFailure(
                    userId = saved.userId,
                    orderId = savedOrderId,
                    mode = mode,
                    stage = "UPDATE_KIS_ACK",
                    cause = e,
                )
            }

            writeAudit(
                orderId = saved.id,
                userId = saved.userId,
                eventType = OrderAuditEventType.KIS_ACK,
                // ADR 0082 T-MAN B-2-extended — DB 컬럼 + JSON 마커 이중 정합
                payload =
                    mapOf(
                        "ODNO" to ackData.kisOrderNo,
                        "KRX_FWDG_ORD_ORGNO" to ackData.kisFwdgOrgno,
                        "source" to intent.source.name,
                    ),
            )
            invalidateAccountSnapshot(
                userId = saved.userId,
                mode = mode,
                reason = "ORDER_ACKED",
                kisOrderNo = ackData.kisOrderNo,
            )

            log.info(
                "OrderRouterKafkaListener ACK: userId={} signalId={} source={} code={} side={} ODNO={}",
                intent.userId,
                intent.signalId,
                intent.source,
                intent.code,
                intent.side,
                ackData.kisOrderNo,
            )

            // ADR 0115 §P2-2 — KIS ACK 성공(거부 streak 끊김) → 해당 전략·종목 SELL 거부 카운터 reset.
            sellRejectAutoPauseTracker.resetOnSuccess(saved)

            // ADR 0114 §4-3 P3(Codex pending-buy 흡수) — AUTO BUY 제출~체결 사이 PENDING_BUY watch(체결 통보 수신 구독 보장).
            //   체결 시 AutoExitOrderCoordinator가 release + HOLDING 등록으로 승격. 실패해도 자본 path 무영향(graceful).
            if (intent.side == OrderSide.BUY && intent.source == OrderSource.AUTO) {
                holdingDemandRegistrarProvider.getIfAvailable()?.let { registrar ->
                    runCatching { registrar.watchPendingBuy(saved.id, intent.code) }
                        .onFailure { e -> log.warn("PENDING_BUY watch 실패(무시): code={} err={}", intent.code, e.message) }
                }
            }

            // Step 11: Kafka commit
            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "OrderRouterKafkaListener 처리 실패 (자본 path — ack 보류, 재처리): offset={} key={} err={}",
                record.offset(),
                record.key(),
                e.message,
                e,
            )
            // 자본 path이므로 unexpected 예외는 ack 보류 — Kafka가 재처리.
            // 1회 retry 후 동일 실패 시 운영 알림(backlog DLQ) 필요.
        }
    }

    /**
     * Step 5 — REQUESTED 상태 영속.
     *
     * **반환 분기 (§62 hotfix — caller 로그 misleading 정정)**:
     * - [PersistResult.NotWired] — Repository bean 미합류 (Phase 2 미완)
     * - [PersistResult.SaveFailed] — Repository.save 자체가 Exception 발생 (FK violation·연결 끊김 등)
     * - [PersistResult.Ok] — 정상 영속 + saved row 반환
     */
    private fun persistRequested(
        intent: OrderIntent,
        mode: TradingMode,
    ): PersistResult {
        val repo = orderRequestRepositoryProvider.getIfAvailable() ?: return PersistResult.NotWired
        val request =
            OrderRequest(
                userId = intent.userId,
                signalId = intent.signalId,
                strategyId = intent.strategyId,
                code = intent.code,
                side = intent.side,
                priceType = intent.priceType,
                quantity = intent.quantity,
                limitPrice = intent.limitPrice,
                status = OrderStatus.REQUESTED,
                mode = mode,
                idempotencyKey = intent.idempotencyKey,
                requestedAt = Instant.now(),
                // ADR 0082 T-MAN B-2-extended — Kafka payload source 그대로 DB 영속 (AUTO/MANUAL 1급 시민)
                source = intent.source,
                // ADR 0115 §2-P2-⑥ — 발사 출처 키 사슬 영속 (미발행 시 source fallback 분류)
                orderTrigger = resolveOrderTrigger(intent),
                triggerRefId = resolveTriggerRefId(intent),
            )
        return try {
            PersistResult.Ok(repo.save(request))
        } catch (e: Exception) {
            log.error("OrderRequestRepository.save 실패: userId={} signalId={} err={}", intent.userId, intent.signalId, e.message)
            PersistResult.SaveFailed(e.message ?: "unknown")
        }
    }

    /**
     * 발사 출처 enum 해석 (ADR 0115 §2-P2-⑥ 키 사슬 무결성).
     *
     * intent payload가 명시 [OrderTrigger]를 발행했으면 그대로(STRATEGY_SIGNAL·AUTO_EXIT·STOP_LOSS). 미발행(legacy
     * producer·구버전 payload)이면 source·signalId 조합으로 fallback 분류 — "출처 모르는 주문" 0 보장:
     *   MANUAL → MANUAL · EXTERNAL → EXTERNAL · AUTO+signalId>0 → STRATEGY_SIGNAL · AUTO+signalId=0 → AUTO_EXIT.
     */
    private fun resolveOrderTrigger(intent: OrderIntent): OrderTrigger =
        intent.orderTrigger
            ?: when (intent.source) {
                OrderSource.MANUAL -> OrderTrigger.MANUAL
                OrderSource.EXTERNAL -> OrderTrigger.EXTERNAL
                OrderSource.AUTO -> if (intent.signalId > 0L) OrderTrigger.STRATEGY_SIGNAL else OrderTrigger.AUTO_EXIT
            }

    /**
     * 유발 근거 실키 해석 (ADR 0115 §2-P2-⑥). payload 명시 키 우선(0·null은 미보존 취급), 미발행 시
     * STRATEGY_SIGNAL은 signalId, AUTO 청산은 strategyId로 복원(0 sentinel은 NULL 유지 — "키 없음" 위장 폐지).
     */
    private fun resolveTriggerRefId(intent: OrderIntent): Long? =
        intent.triggerRefId?.takeIf { it != 0L }
            ?: when (resolveOrderTrigger(intent)) {
                OrderTrigger.STRATEGY_SIGNAL -> intent.signalId.takeIf { it != 0L }
                OrderTrigger.AUTO_EXIT, OrderTrigger.STOP_LOSS, OrderTrigger.VI_AUTO_EXIT, OrderTrigger.PARTIAL_TAKE_PROFIT ->
                    intent.strategyId.takeIf { it != 0L }
                OrderTrigger.MANUAL, OrderTrigger.EXTERNAL -> null
            }

    private fun findWsPreinsertBeforeAck(
        orderRepo: OrderRequestRepository?,
        kisOrderNo: String,
    ): OrderRequest? {
        if (orderRepo == null) return null
        return try {
            orderRepo
                .findByKisOrderNo(kisOrderNo)
                ?.takeIf { it.idempotencyKey == "WS_PREINSERT:$kisOrderNo" && it.strategyId == 0L }
        } catch (e: Exception) {
            log.warn("WS_PREINSERT 조회 실패 — strategy_position replay 감지 skip: ODNO={} err={}", kisOrderNo, e.message)
            null
        }
    }

    private fun replayStrategyPositionAfterWsPreinsertMerge(
        wsPreinsert: OrderRequest?,
        canonicalRequest: OrderRequest,
    ) {
        if (wsPreinsert?.id == null) return
        val replayService = strategyPositionExecutionReplayServiceProvider?.getIfAvailable() ?: return
        val replayed =
            runCatching { replayService.replayIfMissing(canonicalRequest, "WS_PREINSERT_ACK_MERGE") }
                .onFailure { e ->
                    log.warn(
                        "WS_PREINSERT strategy_position replay 실패 — ACK 병합은 유지: preinsertOrderId={} canonicalOrderId={} err={}",
                        wsPreinsert.id,
                        canonicalRequest.id,
                        e.message,
                    )
                }.getOrDefault(0)
        if (replayed > 0) {
            log.warn(
                "WS_PREINSERT strategy_position replay: preinsertOrderId={} canonicalOrderId={} strategyId={} code={} replayed={}",
                wsPreinsert.id,
                canonicalRequest.id,
                canonicalRequest.strategyId,
                canonicalRequest.code,
                replayed,
            )
        }
    }

    /**
     * [persistRequested] 결과 분기 — caller 로그 메시지 분리용 (§62 hotfix).
     */
    internal sealed class PersistResult {
        /** Repository ObjectProvider가 bean을 찾지 못함 — Phase 2 미완 상태. */
        object NotWired : PersistResult()

        /** Repository.save() 호출 자체가 Exception 발생 — DB 단 결함 (FK violation·연결 끊김 등). */
        data class SaveFailed(
            val reason: String,
        ) : PersistResult()

        /** 정상 영속 — saved row 반환. */
        data class Ok(
            val saved: OrderRequest,
        ) : PersistResult()
    }

    private data class SyncGateResult(
        val allowed: Boolean,
        val reason: String,
    ) {
        companion object {
            val ALLOWED = SyncGateResult(true, "OK")
        }
    }

    /**
     * 신규 주문 직전 KIS 당일 체결조회와 DB backfill 완결성을 확인한다 (자본 정합 hotfix 2026-06-08 — REAL·MOCK 동일화).
     *
     * 이 검사는 "DB와 KIS가 영구적으로 완전히 같다"를 증명하려는 검사가 아니다. 주문 직전 KIS 체결 원장을
     * 방금 읽고, DB 손익 계산 재료로 반영 가능한 상태인지 확인하는 fail-closed 게이트다.
     *
     * ## REAL·MOCK 동일화 (과거 `ensureKisExecutionSyncIfReal` REAL-only 게이트 제거)
     * 과거 `if (mode != REAL) return ALLOWED`로 MOCK(VPS 페이퍼) 주문은 sync 게이트를 건너뛰어, WS 체결통보
     * 누락 시 backfill 보정이 돌지 않아 ACK 주문이 정체했다. 체결 보정 정합은 실돈 여부와 무관하므로 두 모드 모두
     * 실행한다(메모리 `feedback_kis_key_realonly_for_data` — 모드 분기는 실제 주문 액션에만).
     * account type은 주문 [mode]에서 파생 — REAL → [AccountType.REAL](실계좌), MOCK → [AccountType.MOCK](VPS 페이퍼).
     * KIS endpoint tr_id·base-url 분기는 [kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisOrderInquireCcldHttpAdapter]가
     * accountType 기반으로 이미 처리한다.
     */
    private fun ensureKisExecutionSync(
        intent: OrderIntent,
        mode: TradingMode,
    ): SyncGateResult {
        val accountType = if (mode == TradingMode.REAL) AccountType.REAL else AccountType.MOCK
        val syncService =
            executionBackfillServiceProvider.getIfAvailable()
                ?: return SyncGateResult(false, "KIS_EXECUTION_SYNC_STALE:SYNC_SERVICE_UNAVAILABLE")

        val result =
            try {
                syncService.syncUserTodayExecutions(intent.userId, accountType)
            } catch (e: Exception) {
                log.error("KIS execution sync guard 예외 — userId={} signalId={} err={}", intent.userId, intent.signalId, e.message, e)
                return SyncGateResult(false, "KIS_EXECUTION_SYNC_STALE:SYNC_EXCEPTION:${e.message ?: "UNKNOWN"}")
            }

        if (result.isComplete) {
            log.info(
                "KIS execution sync guard PASS — userId={} accountType={} fetched={} inserted={} alreadySynced={}",
                intent.userId,
                accountType.name,
                result.fetched,
                result.inserted,
                result.alreadySynced,
            )
            return SyncGateResult.ALLOWED
        }

        return SyncGateResult(false, "KIS_EXECUTION_SYNC_STALE:${result.reasonCode}")
    }

    /**
     * ADR 0109 — REAL canary 전역 hard cap 게이트.
     *
     * RiskConfig·전략 설정과 **독립적인** 마지막 자본 안전선. canary 1회 동안 전략 설정 오류·budget 오산·
     * 다전략 누적으로 실수해도 이 cap 이상은 절대 나가지 않도록 OrderRouter 단에서 강제 차단한다.
     *
     * ### 활성 조건 (fail-open)
     * - MOCK 모드: 전체 비활성 (ENV 무관) — canary는 REAL 안전선이므로.
     * - REAL 모드: 각 ENV `> 0` 일 때만 해당 cap 활성. `≤ 0`(미설정 포함)이면 통과 = 일반 REAL 운용.
     *
     * ### SELL 전면 면제 (2026-06-12 F8 hotfix · 최고 관리자 결재 LOCK)
     * **숏(공매도) 부재** — 매도는 보유 수량이 자연 상한이다. canary cap은 본질적으로 "매수가 실수로 과대 노출되는
     * 것"을 막는 진입 안전선이며, 매도(익절·손절·DSL 청산·수동 청산)는 신호가 나오면 팔면 끝인 청산 행위다. 결재
     * 문구: "매수일 때나 이런 룰인 거지 매도나 익절 손절일 때는 이 조건을 보면 안 돼. 우리 시스템에는 숏이 없어.
     * 매도는 신호 나오면 팔면 끝." 따라서 `side == SELL`이면 **QTY·NOTIONAL cap 검사를 전부 건너뛴다**(매수는 현행
     * 유지). MAX_ORDERS_PER_DAY는 이미 BUY-only(PR #927). 라이브 13:31 incident — 수동 시장가 SELL이
     * NOTIONAL cap의 `PRICE_UNAVAILABLE`(시장가 매도 금액 검증용 현재가 조회 실패 → fail-closed)로 거부되어
     * **청산이 차단**된 자본 보호 역전을 본 면제가 닫는다.
     *
     * ### 3 cap (ADR 0109 §2 표 · BUY 한정)
     * 1. `MAGICJAR_CANARY_MAX_ORDER_QTY` — 주문 1건 수량 절대 상한 → reason `CANARY_QTY_CAP` (BUY only)
     * 2. `MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW` — 주문 1건 notional(qty × price) 절대 상한 → `CANARY_NOTIONAL_CAP` (BUY only)
     *    - price source: LIMIT은 `limitPrice`, MARKET은 `priceAtSignal` (ADR 0109 §2-5).
     *    - 둘 다 null/0 이하면 notional 산정 불가 → fail-closed로 차단(`CANARY_NOTIONAL_CAP:PRICE_UNAVAILABLE`).
     * 3. `MAGICJAR_CANARY_MAX_ORDERS_PER_DAY` — 당일(KST) BUY 주문 횟수 전역 상한 → `CANARY_DAILY_ORDER_CAP`
     *    - order_request `mode=REAL` · `side=BUY` · `status<>REJECTED` 당일 count(DB 단일 권위, ADR 0109 §2-4)
     *      ≥ cap 이면 차단. SELL(익절·손절·DSL 청산)은 cap 무관 — 매수 cap 도달 후 청산이 거부되는
     *      자본 보호 역전 차단(2026-06-12 라이브 incident · 최고 관리자 결재: "왕복 1회 = 1카운트").
     *
     * @return 차단 시 reject reason 문자열, 통과(또는 비활성·SELL 면제) 시 null.
     */
    internal fun evaluateCanaryHardCap(
        intent: OrderIntent,
        mode: TradingMode,
    ): String? {
        if (mode != TradingMode.REAL) return null

        // SELL 전면 면제 (F8 — 숏 부재로 매도는 보유량이 자연 상한). QTY·NOTIONAL cap을 건너뛴다.
        // (MAX_ORDERS_PER_DAY는 cap 3 내부에서 이미 BUY-only count — 본 early return으로 일관 표현.)
        if (intent.side == OrderSide.SELL) {
            log.debug(
                "CanaryHardCap SELL 면제 (숏 부재 — 매도 청산은 cap 무관): userId={} signalId={} code={} qty={}",
                intent.userId,
                intent.signalId,
                intent.code,
                intent.quantity,
            )
            return null
        }

        // cap 1 — 수량 상한 (BUY only)
        if (canaryMaxOrderQty > 0 && intent.quantity > canaryMaxOrderQty) {
            return "CANARY_QTY_CAP:${intent.quantity}>$canaryMaxOrderQty"
        }

        // cap 2 — notional 상한 (qty × price) (BUY only — SELL은 위에서 early return)
        if (canaryMaxOrderNotionalKrw > 0) {
            val unitPrice = canaryNotionalPrice(intent)
            if (unitPrice == null || unitPrice <= BigDecimal.ZERO) {
                // notional cap 활성인데 가격을 못 구하면 안전선 무력화 위험 → fail-closed.
                return "CANARY_NOTIONAL_CAP:PRICE_UNAVAILABLE"
            }
            val notional = unitPrice.multiply(BigDecimal.valueOf(intent.quantity.toLong()))
            val cap = BigDecimal.valueOf(canaryMaxOrderNotionalKrw)
            if (notional > cap) {
                return "CANARY_NOTIONAL_CAP:${notional.toPlainString()}>$canaryMaxOrderNotionalKrw"
            }
        }

        // cap 3 — 당일 REAL 주문 횟수 전역 상한 (DB 단일 권위)
        if (canaryMaxOrdersPerDay > 0) {
            val repo = orderRequestRepositoryProvider.getIfAvailable()
            if (repo == null) {
                // 카운트 권위(DB) 미합류 시 cap을 셀 수 없음 → fail-closed (canary 안전선 보존).
                log.error(
                    "CanaryHardCap — OrderRequestRepository 미합류로 일일 count 불가 (fail-closed): userId={} signalId={}",
                    intent.userId,
                    intent.signalId,
                )
                return "CANARY_DAILY_ORDER_CAP:COUNT_UNAVAILABLE"
            }
            val (from, to) = kstDayBoundsUtc(Instant.now())
            val todayRealCount =
                try {
                    repo.countRealOrdersBetween(from, to)
                } catch (e: Exception) {
                    log.error(
                        "CanaryHardCap — 일일 REAL count 조회 실패 (fail-closed): userId={} signalId={} err={}",
                        intent.userId,
                        intent.signalId,
                        e.message,
                    )
                    return "CANARY_DAILY_ORDER_CAP:COUNT_FAILED"
                }
            if (todayRealCount >= canaryMaxOrdersPerDay) {
                return "CANARY_DAILY_ORDER_CAP:$todayRealCount>=$canaryMaxOrdersPerDay"
            }
        }

        return null
    }

    /**
     * canary notional cap용 단가 산정 — LIMIT은 [OrderIntent.limitPrice], MARKET은 [OrderIntent.priceAtSignal]
     * (ADR 0109 §2-5). 가격 부재 시 null (caller가 fail-closed 처리).
     */
    private fun canaryNotionalPrice(intent: OrderIntent): BigDecimal? =
        when (intent.priceType) {
            OrderPriceType.LIMIT -> intent.limitPrice ?: intent.priceAtSignal
            else -> intent.priceAtSignal ?: intent.limitPrice
        }

    /**
     * 계좌 availability snapshot guard.
     *
     * 주문 hot path 에서 KIS REST를 직접 호출하지 않는다. Redis snapshot 이 fresh 하고
     * BUY cash 또는 SELL sellable quantity 부족을 확정적으로 말할 때만 optional 차단한다.
     */
    internal fun evaluateAccountAvailabilitySnapshot(
        intent: OrderIntent,
        mode: TradingMode,
    ): String? {
        if (!accountAvailabilitySnapshotGuardEnabled) return null
        val port =
            accountAvailabilitySnapshotPortProvider?.getIfAvailable()
                ?: run {
                    log.warn(
                        "account availability snapshot guard enabled but snapshot port unavailable — pass-through: userId={} signalId={}",
                        intent.userId,
                        intent.signalId,
                    )
                    return null
                }

        val snapshot =
            try {
                port.read(intent.userId, mode)
            } catch (e: Exception) {
                log.warn(
                    "account availability snapshot read failed — pass-through: userId={} signalId={} code={} side={} err={}",
                    intent.userId,
                    intent.signalId,
                    intent.code,
                    intent.side,
                    e.message,
                )
                return null
            }
                ?: run {
                    log.info(
                        "account availability snapshot miss — pass-through: userId={} signalId={} code={} side={}",
                        intent.userId,
                        intent.signalId,
                        intent.code,
                        intent.side,
                    )
                    return null
                }

        val maxAge = Duration.ofSeconds(accountAvailabilityMaxAgeSeconds)
        if (!snapshot.isFresh(Instant.now(), maxAge)) {
            log.info(
                "account availability snapshot stale — pass-through: userId={} signalId={} code={} side={} refreshedAt={} maxAgeSec={}",
                intent.userId,
                intent.signalId,
                intent.code,
                intent.side,
                snapshot.refreshedAt,
                accountAvailabilityMaxAgeSeconds,
            )
            return null
        }

        log.info(
            "account availability snapshot: userId={} signalId={} code={} side={} cashD2={} sellableQty={} refreshedAt={}",
            intent.userId,
            intent.signalId,
            intent.code,
            intent.side,
            snapshot.cashD2,
            snapshot.sellableQty(intent.code),
            snapshot.refreshedAt,
        )

        if (!accountAvailabilityRejectOnInsufficient) return null
        return snapshotDenyReason(intent, snapshot)
    }

    private fun snapshotDenyReason(
        intent: OrderIntent,
        snapshot: AccountAvailabilitySnapshot,
    ): String? {
        val reason =
            when (intent.side) {
                OrderSide.BUY -> {
                    val orderableAmount = snapshot.cashD2
                    val estimatedNotional = canaryNotionalPrice(intent)?.multiply(BigDecimal(intent.quantity)) ?: return null
                    if (orderableAmount < estimatedNotional) {
                        "INSUFFICIENT_CASH:$orderableAmount<$estimatedNotional"
                    } else {
                        null
                    }
                }
                OrderSide.SELL -> {
                    val availableQty = snapshot.sellableQty(intent.code) ?: return null
                    if (availableQty < intent.quantity) {
                        "INSUFFICIENT_SELLABLE_QTY:$availableQty<${intent.quantity}"
                    } else {
                        null
                    }
                }
            }
        return reason?.let { "KIS_ORDER_AVAILABILITY_SNAPSHOT:$it" }
    }

    /**
     * 주어진 시각이 속한 KST 당일 `[00:00, 다음날 00:00)` 경계를 UTC [Instant] 범위로 변환.
     * canary 일일 count 조회용 (order_request.requested_at은 UTC Instant 영속).
     */
    private fun kstDayBoundsUtc(now: Instant): Pair<Instant, Instant> {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val today = now.atZone(zone).toLocalDate()
        val from = today.atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        return from to to
    }

    /**
     * 현재 시각(`java.time.Instant`)이 영업일 + 정규장(09:00~15:30 KST) 안인지 판정 (장운영시간 게이트 — 최후 방어선).
     *
     * - 정규장 판정: [MarketSessionResolver.resolve] 가 [MarketSession.Regular] 반환 시 (KST 변환은 resolver 내부).
     * - 영업일 판정: [BusinessDayCalendar] (market_calendar DB 권위) 우선, bean 부재·예외 시 resolver businessDayOf fallback.
     */
    internal fun isWithinTradingHours(now: Instant): Boolean {
        val kotlinNow = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli())
        val session = marketSessionResolver.resolve(kotlinNow)
        if (session !is MarketSession.Regular) return false

        val calendar = businessDayCalendarProvider.getIfAvailable()
        val kstDate =
            kotlinNow.toLocalDateTime(kstZone).date.let {
                java.time.LocalDate.of(it.year, it.monthNumber, it.dayOfMonth)
            }
        return if (calendar != null) {
            try {
                calendar.isBusinessDay(kstDate)
            } catch (e: Exception) {
                log.warn("OrderRouterKafkaListener BusinessDayCalendar 조회 실패 — resolver fallback: date={} err={}", kstDate, e.message)
                marketSessionResolver.businessDayOf(kotlinNow) != null
            }
        } else {
            marketSessionResolver.businessDayOf(kotlinNow) != null
        }
    }

    /**
     * ADR 0122 §6 GAP-0 hotfix — intent.strategyId의 전략 origin으로 페이퍼 여부 판정.
     *
     * 라우터 단일 관문 판정 — 발행처 4곳(T-RISK·AutoExit·StopLossWatcher·MANUAL)을 전부 커버.
     * origin 단일 진실원 = strategy 테이블 V81 (payload 복제 0 — ADR 0115 P2-⑥ 발명 0 정합).
     *
     *  - strategyId <= 0 (MANUAL sentinel) → false (수동 주문은 env 모드 따름)
     *  - origin == PAPER_DISCOVERY → true (게이트가 MOCK 강제)
     *  - origin null/기타 → false (V81 legacy = USER_DSL — zero-regression)
     *  - 조회 불가(repo 미합류·예외·전략 미존재):
     *      env=REAL → Left fail-closed (헌법 §11-2 — 잘못된 모드로 실주문 발사보다 거부가 안전)
     *      env=MOCK → ERROR 로그 + false 진행 (명시 degraded — env=MOCK이 어차피 MOCK 강제)
     */
    private fun resolvePaperStrategy(intent: OrderIntent): OrderResult<DenyReason.RealGateDenied, Boolean> {
        if (intent.strategyId <= 0L) return OrderResult.Right(false)
        val realMode = RuntimeModeParser.isRealMode(magicjarMode)
        val repo = consumerStrategyJpaRepositoryProvider?.getIfAvailable()
        if (repo == null) {
            log.error(
                "resolvePaperStrategy ConsumerStrategyJpaRepository 미합류: userId={} strategyId={} realMode={} (헌법 §11-2)",
                intent.userId,
                intent.strategyId,
                realMode,
            )
            return if (realMode) {
                OrderResult.Left(DenyReason.RealGateDenied("PAPER_ORIGIN_UNRESOLVED:REPO_UNAVAILABLE"))
            } else {
                OrderResult.Right(false)
            }
        }
        val entity =
            try {
                repo.findById(intent.strategyId).orElse(null)
            } catch (e: Exception) {
                log.error(
                    "resolvePaperStrategy strategy 조회 실패: userId={} strategyId={} realMode={} err={}",
                    intent.userId,
                    intent.strategyId,
                    realMode,
                    e.message,
                )
                return if (realMode) {
                    OrderResult.Left(DenyReason.RealGateDenied("PAPER_ORIGIN_UNRESOLVED:LOOKUP_FAILED"))
                } else {
                    OrderResult.Right(false)
                }
            }
        if (entity == null) {
            log.error(
                "resolvePaperStrategy strategy 미존재: userId={} strategyId={} realMode={} (intent 발행 후 삭제 추정)",
                intent.userId,
                intent.strategyId,
                realMode,
            )
            return if (realMode) {
                OrderResult.Left(DenyReason.RealGateDenied("PAPER_ORIGIN_UNRESOLVED:STRATEGY_NOT_FOUND"))
            } else {
                OrderResult.Right(false)
            }
        }
        // origin null = V81 legacy = USER_DSL 취급 (zero-regression — ConsumerStrategyJpaEntity 주석 정합)
        return OrderResult.Right(entity.origin == StrategyOrigin.PAPER_DISCOVERY.name)
    }

    /**
     * Step 3·4 REJECTED 공통 처리 (게이트 단계 — order_request INSERT(REJECTED) + audit + Kafka publish).
     */
    private fun handleRejected(
        intent: OrderIntent,
        mode: TradingMode,
        reason: String,
        auditType: OrderAuditEventType,
    ) {
        val now = Instant.now()
        val rejected =
            OrderRequest(
                userId = intent.userId,
                signalId = intent.signalId,
                strategyId = intent.strategyId,
                code = intent.code,
                side = intent.side,
                priceType = intent.priceType,
                quantity = intent.quantity,
                limitPrice = intent.limitPrice,
                status = OrderStatus.REJECTED,
                mode = mode,
                idempotencyKey = intent.idempotencyKey,
                rejectReason = reason,
                requestedAt = now,
                completedAt = now,
                // ADR 0082 T-MAN B-2-extended — 게이트 REJECTED도 source 보존 (manual 주문 차단 audit 가능)
                source = intent.source,
                // ADR 0115 §2-P2-⑥ — REJECTED도 발사 출처 보존 (차단된 주문도 어느 출처였는지 audit)
                orderTrigger = resolveOrderTrigger(intent),
                triggerRefId = resolveTriggerRefId(intent),
            )

        val saved =
            try {
                orderRequestRepositoryProvider.getIfAvailable()?.save(rejected) ?: rejected
            } catch (e: Exception) {
                log.error("REJECTED save 실패: userId={} err={}", intent.userId, e.message)
                rejected
            }

        // ADR 0082 T-MAN B-2-extended — DB 컬럼 + JSON 마커 이중 정합 (audit trail)
        writeAudit(
            saved.id,
            saved.userId,
            auditType,
            mapOf("reason" to reason, "source" to intent.source.name),
        )
        orderRejectedKafkaProducer.publish(saved, reason)
        reclaimTradeFrequencyRecordIfGateRejected(intent, reason)
        invalidateAccountSnapshot(saved, reason = "ORDER_REJECTED", kisOrderNo = saved.kisOrderNo)
        // ADR 0115 §P2-2 — gate SELL 거부도 누적 추적(임계 도달 시 자동 정지 + 1회 알림).
        sellRejectAutoPauseTracker.onOrderRejected(saved)
        // Polling → STOMP push (2026-05-20) — gate REJECTED (TradingModeGate · KillSwitch).
        // 게이트 차단은 KIS 요청 자체를 안 보냈으므로 사실상 잔고 변동 없음 — 그러나 frontend "주문 거절" UX 정합으로
        // invalidate trigger를 동일 path로 통일 (사용자가 잔고창 새로고침해도 무해 — cache TTL 10s 내 KIS 0 호출).
        accountBalanceChangedKafkaProducer.publish(
            userId = saved.userId,
            mode = saved.mode,
            reason = AccountBalanceChangedKafkaProducer.REASON_REJECTED,
            triggerKisOrderNo = saved.kisOrderNo,
        )
        log.warn(
            "OrderRouterKafkaListener REJECTED: userId={} signalId={} source={} reason={}",
            intent.userId,
            intent.signalId,
            intent.source,
            reason,
        )
    }

    private fun reclaimTradeFrequencyRecordIfGateRejected(
        intent: OrderIntent,
        reason: String,
    ) {
        if (intent.source != OrderSource.AUTO || intent.signalId <= 0L || intent.strategyId <= 0L) return
        val guard = tradeFrequencyGuardProvider?.getIfAvailable() ?: return

        try {
            guard.removeRecord(intent.strategyId, intent.userId, intent.signalId)
            log.info(
                "trade-frequency record reclaimed after pre-submit reject: userId={} strategyId={} signalId={} reason={}",
                intent.userId,
                intent.strategyId,
                intent.signalId,
                reason,
            )
        } catch (e: Exception) {
            log.warn(
                "trade-frequency record reclaim 실패 (reject 흐름은 유지): userId={} strategyId={} signalId={} reason={} err={}",
                intent.userId,
                intent.strategyId,
                intent.signalId,
                reason,
                e.message,
            )
        }
    }

    /**
     * Step 7 — KIS REST submit + 5xx 1회 retry (§14-2 step 7 + §14-4 risk 매트릭스 + ADR 0084 P0-2).
     *
     * ### ADR 0084 P0-2 — PENDING_RECONCILE 분기 (외부 코드 리뷰 결함 #3 fix)
     * - 5xx 재시도 후 최종 실패: 기존 `DenyReason.KisException("KIS_5XX:...")` → `KisTimeoutPendingReconcile` 분기 갱신
     * - socket timeout: `WebClientRequestException` 또는 `java.util.concurrent.TimeoutException` chain
     *   → `KisTimeoutPendingReconcile` 분기
     * - 기타 일반 Exception(parse 실패·NPE 등): 기존 `KisException` 유지 (KIS 응답 못 받았으므로 REJECTED 확정)
     *
     * caller(`onOrderIntent`)는 `KisTimeoutPendingReconcile` 시 REJECTED 대신 PENDING_RECONCILE 마킹 + audit.
     * OrderReconciliationWorker가 1분 cron으로 KIS inquire-daily-ccld 검증 후 실제 상태로 전이.
     */
    private fun submitWithRetry(
        intent: OrderIntent,
        mode: TradingMode,
    ): OrderResult<DenyReason, kr.spdaewoon.magicjar.domain.order.execution.KisOrderAck> {
        var attempt = 0
        while (true) {
            try {
                return kisOrderPort.submit(intent, mode)
            } catch (e: WebClientResponseException) {
                if (e.statusCode.is5xxServerError && attempt == 0) {
                    attempt++
                    log.warn(
                        "KIS REST 5xx 1회 retry: userId={} signalId={} status={}",
                        intent.userId,
                        intent.signalId,
                        e.statusCode,
                    )
                    Thread.sleep(RETRY_BACKOFF_MS)
                    continue
                }
                log.error(
                    "KIS REST 5xx 재시도 후 실패 → PENDING_RECONCILE (ADR 0084 P0-2): userId={} signalId={} status={}",
                    intent.userId,
                    intent.signalId,
                    e.statusCode,
                )
                // ADR 0084 P0-2 — 5xx 재시도 후 실패는 KIS 측 ambiguous 상태 (실제 접수됐을 수 있음).
                return OrderResult.Left(
                    DenyReason.KisTimeoutPendingReconcile("KIS_5XX:${e.statusCode.value()}"),
                )
            } catch (e: java.util.concurrent.TimeoutException) {
                // ADR 0084 P0-2 — socket timeout: 클라이언트 ACK 미수신 but KIS 접수 가능.
                log.error(
                    "KIS REST timeout → PENDING_RECONCILE (ADR 0084 P0-2): userId={} signalId={} err={}",
                    intent.userId,
                    intent.signalId,
                    e.message,
                )
                return OrderResult.Left(
                    DenyReason.KisTimeoutPendingReconcile("KIS_TIMEOUT:${e.message ?: "TimeoutException"}"),
                )
            } catch (e: Exception) {
                // ADR 0084 P0-2 — reactor/netty wraps timeout via cause chain (ReadTimeoutException ·
                // TimeoutException). cause chain 검사 + message 검사로 timeout 시그널 감지.
                val isTimeout =
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                        generateSequence(e.cause) { it.cause }
                            .any { c ->
                                c is java.util.concurrent.TimeoutException ||
                                    c.javaClass.simpleName.contains("Timeout", ignoreCase = true)
                            }
                if (isTimeout) {
                    log.error(
                        "KIS REST timeout(wrapped) → PENDING_RECONCILE (ADR 0084 P0-2): userId={} signalId={} err={}",
                        intent.userId,
                        intent.signalId,
                        e.message,
                    )
                    return OrderResult.Left(
                        DenyReason.KisTimeoutPendingReconcile("KIS_TIMEOUT_WRAPPED:${e.message ?: "unknown"}"),
                    )
                }
                log.error("KIS REST submit 예외: userId={} signalId={} err={}", intent.userId, intent.signalId, e.message)
                return OrderResult.Left(DenyReason.KisException(e.message ?: "UNKNOWN"))
            }
        }
    }

    /**
     * audit row 영속 — Repository 미합류 시 graceful skip + log.warn.
     */
    private fun writeAudit(
        orderId: Long?,
        userId: String,
        eventType: OrderAuditEventType,
        payload: Map<String, Any?>,
    ) {
        val repo = orderAuditEventRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn(
                "writeAudit skip — OrderAuditEventRepository 미합류 (Phase 2 미완): orderId={} userId={} eventType={}",
                orderId,
                userId,
                eventType,
            )
            return
        }
        try {
            repo.save(
                OrderAuditEvent(
                    orderId = orderId,
                    userId = userId,
                    eventType = eventType,
                    payloadJson = objectMapper.writeValueAsString(payload),
                    createdAt = Instant.now(),
                ),
            )
        } catch (e: Exception) {
            log.error("writeAudit 실패: orderId={} userId={} eventType={} err={}", orderId, userId, eventType, e.message)
        }
    }

    private fun handlePostKisDbSyncFailure(
        userId: String,
        orderId: Long,
        mode: TradingMode,
        stage: String,
        cause: Exception,
    ) {
        writeAudit(
            orderId = orderId,
            userId = userId,
            eventType = OrderAuditEventType.ORDER_DB_SYNC_FAILED,
            payload =
                mapOf(
                    "stage" to stage,
                    "reason" to POST_KIS_DB_SYNC_FAILED_REASON,
                    "error" to (cause.message ?: "unknown"),
                    "mode" to mode.name,
                ),
        )

        if (mode != TradingMode.REAL) return

        val killSwitchRepo = killSwitchJpaRepositoryProvider.getIfAvailable()
        if (killSwitchRepo == null) {
            log.error(
                "REAL post-KIS DB sync 실패 후 KillSwitchJpaRepository 미합류: userId={} orderId={} stage={}",
                userId,
                orderId,
                stage,
            )
            return
        }

        try {
            killSwitchRepo.enableKillSwitch(
                userId = userId,
                reason = "$POST_KIS_DB_SYNC_FAILED_REASON:$stage",
                updatedBy = "consumer-order-router",
            )
            log.error(
                "KillSwitch auto-ON: REAL post-KIS DB sync 실패 userId={} orderId={} stage={}",
                userId,
                orderId,
                stage,
            )
        } catch (e: Exception) {
            log.error(
                "KillSwitch auto-ON 실패: userId={} orderId={} stage={} err={}",
                userId,
                orderId,
                stage,
                e.message,
                e,
            )
        }
    }

    private fun invalidateAccountSnapshot(
        order: OrderRequest,
        reason: String,
        kisOrderNo: String? = order.kisOrderNo,
    ) = invalidateAccountSnapshot(order.userId, order.mode, reason, kisOrderNo)

    private fun invalidateAccountSnapshot(
        userId: String,
        mode: TradingMode,
        reason: String,
        kisOrderNo: String?,
    ) {
        accountSnapshotCoordinatorProvider
            ?.getIfAvailable()
            ?.invalidate(
                userId = userId,
                mode = mode,
                reason = reason,
                triggerKisOrderNo = kisOrderNo,
            )
    }

    /**
     * `order.intent.v1` payload deserialize (ADR 0067 OrderIntentKafkaProducer schema 정합).
     */
    internal fun deserialize(value: String?): OrderIntent? {
        if (value.isNullOrBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            val signalId = (payload["signalId"] as? Number)?.toLong() ?: return null
            val strategyId = (payload["strategyId"] as? Number)?.toLong() ?: return null
            val userId = payload["userId"]?.toString() ?: return null
            val code = payload["code"]?.toString() ?: return null
            val sideName = payload["side"]?.toString() ?: return null
            val side = OrderSide.values().firstOrNull { it.name == sideName } ?: return null
            // ADR 0067 publisher가 priceType·quantity·limitPrice 미발행 — T-O 진입 시 default 채움.
            val priceTypeName = payload["priceType"]?.toString()
            val priceType =
                priceTypeName?.let { name -> OrderPriceType.values().firstOrNull { it.name == name } }
                    ?: OrderPriceType.MARKET
            val quantity = (payload["quantity"] as? Number)?.toInt() ?: 1
            val limitPrice =
                when (val raw = payload["limitPrice"]) {
                    is Number -> BigDecimal.valueOf(raw.toDouble())
                    is String -> raw.toBigDecimalOrNull()
                    else -> null
                }
            val idempotencyKey =
                payload["idempotencyKey"]?.toString()
                    ?: "$signalId:$userId"
            val emittedAtSec =
                when (val raw = payload["emittedAt"]) {
                    is Number -> raw.toLong()
                    is String -> raw.toLongOrNull() ?: Instant.now().epochSecond
                    else -> Instant.now().epochSecond
                }
            // ADR 0082 T-MAN B-2-extended — source 필드 매핑 (default AUTO, backward compat)
            // 비정상 값(예: "FOO")은 IllegalArgumentException → outer catch에서 poison pill skip.
            val sourceStr = payload["source"]?.toString() ?: "AUTO"
            val source = OrderSource.valueOf(sourceStr)
            // ADR 0109 §2-5 — canary notional cap MARKET price source (T-RISK publisher가 발행, 미발행 시 null)
            val priceAtSignal =
                when (val raw = payload["priceAtSignal"]) {
                    is Number -> BigDecimal.valueOf(raw.toDouble())
                    is String -> raw.toBigDecimalOrNull()
                    else -> null
                }
            // ADR 0115 §2-P2-⑥ — 발사 출처 enum 매핑 (미발행 시 null → OrderRequest 영속 후 source·signalId fallback 분류).
            // 비정상 enum 값은 safeEnum 으로 null fallback(poison pill 회피 — 키 부재가 부팅 실패로 번지지 않음).
            val orderTrigger =
                payload["orderTrigger"]?.toString()?.let { runCatching { OrderTrigger.valueOf(it) }.getOrNull() }
            val triggerRefId =
                when (val raw = payload["triggerRefId"]) {
                    is Number -> raw.toLong().takeIf { it != 0L }
                    is String -> raw.toLongOrNull()?.takeIf { it != 0L }
                    else -> null
                }
            OrderIntent(
                id = signalId,
                userId = userId,
                signalId = signalId,
                strategyId = strategyId,
                code = code,
                side = side,
                priceType = priceType,
                quantity = quantity,
                limitPrice = limitPrice,
                idempotencyKey = idempotencyKey,
                requestedAt = Instant.ofEpochSecond(emittedAtSec),
                source = source,
                priceAtSignal = priceAtSignal,
                orderTrigger = orderTrigger,
                triggerRefId = triggerRefId,
            )
        } catch (e: Exception) {
            log.error("OrderRouterKafkaListener.deserialize 실패: err={}", e.message)
            null
        }
    }
}
