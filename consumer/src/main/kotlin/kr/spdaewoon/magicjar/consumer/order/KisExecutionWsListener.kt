package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.account.AccountSnapshotCoordinator
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import reactor.core.scheduler.Schedulers
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS WS H0STCNI0/H0STCNI9 체결 통보 처리 (ADR 0068 §14-6 LOCK + ADR 0084 P0-3 race fix).
 *
 * ### raw 출처
 * examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446 (def ccnl_notice)
 *
 * ### tr_id
 * - H0STCNI0 = 실전 체결 통보
 * - H0STCNI9 = 모의 체결 통보
 *
 * ### 흐름 분기 LOCK (§14-6)
 * ```
 * if (CNTG_YN=="1" && RFUS_YN=="Y") → 거부 통보 (§14-2 step 7 백업)
 * if (CNTG_YN=="1" && ACPT_YN=="1") → 주문 접수 통보 (KIS_ACK 백업, 중복 시 멱등)
 * if (CNTG_YN=="1" && ACPT_YN=="3") → 취소 접수 통보 (status=CANCELLED + KIS_CANCEL_ACK)
 * if (CNTG_YN=="2") → 체결 통보:
 *   - findByKisOrderNo(ODER_NO)
 *   - order_execution INSERT(CNTG_QTY, CNTG_UNPR, STCK_CNTG_HOUR)
 *   - audit FILL_RECEIVED
 *   - sum = sumExecutedQuantityByOrder(orderId)
 *   - sum < qty → PARTIALLY_FILLED + PARTIAL_FILL + STOMP partial
 *   - sum == qty → FILLED + FULL_FILL + STOMP filled + Kafka order.filled
 *   - sum > qty → EXECUTION_OVERFLOW + skip
 * ```
 *
 * ### ADR 0084 P0-3 race fix — pre-insert 패턴 (architect 권장)
 * 외부 코드 리뷰 결함 #4 — KIS WS 체결 통보가 REST submit ACK 응답보다 먼저 도달 가능.
 * 기존 path: `findByKisOrderNo == null` → graceful skip → 체결 row 영속 0 → sum 계산 누락 → FILLED 전환 안 됨 → audit 깨짐.
 *
 * 신규 path (pre-insert + retry):
 *  1. `findByKisOrderNo == null` 시 ─ 즉시 skip 대신 짧은 retry (50ms × 3회) — ACK 처리 race window 흡수
 *  2. retry 후에도 null이면 ─ pre-insert 패턴: OrderRequest stub row INSERT (kisOrderNo set + status=ACK + signalId=0 sentinel + idempotencyKey="WS_PREINSERT:{kisOrderNo}")
 *     - 후속 REST ACK 응답이 도착하면 [OrderRouterKafkaListener] step 8이 동일 kisOrderNo로 update 갱신 (idempotency 정합)
 *     - 또는 [KisOrderInquireCcldHttpAdapter] REST backfill이 동일 kisOrderNo row를 cross-ref
 *  3. event_id 멱등 가드 ─ `{kisOrderNo}:{stckCntgHour}:{cntgQty}` 합성 후 [OrderExecutionRepository.existsByEventId] true 시 skip
 *     - 단, 주문이 아직 미체결 상태라면 동일 초·동일 가격·동일 수량 부분체결 collision 가능성이 있어 REST 체결 동기화를 즉시 예약
 *
 * ### eventId 합성 규칙 (ADR 0084 §2-3 V35 정합)
 * `{odno}:{stckCntgHour}:{cntgQty}` — WS + REST inquire-daily-ccld backfill 양 경로 동일 합성.
 * Flyway V35 `uq_execution_event_id` partial UNIQUE 인덱스가 1차 DB level dedup, 본 listener의 existsByEventId가 2차 application level dedup.
 *
 * ### Phase 1 wiring 한계 (자본 보호)
 * 본 클래스는 [processMessage] 핵심 흐름을 구현. 실제 KIS WS subscribe wiring(KisWebSocketBridge 통합)은
 * Phase 2 backend-core·KisWebSocketBridge 보강 시점에 합류. 본 PR은 핵심 분기 로직 + 단위 테스트로
 * 자본 보호 risk cover (§14-7 LOCK).
 *
 * ### Phase 2 graceful fallback (헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * OrderRequestRepository·OrderExecutionRepository·OrderAuditEventRepository 미합류 시 — 본 listener는
 * ObjectProvider로 graceful skip + log.warn (자본 변동 path이므로 사용자 알림 의무).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §2-8)
 */
@Component
class KisExecutionWsListener(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val orderFilledKafkaProducer: OrderFilledKafkaProducer,
    private val orderRejectedKafkaProducer: OrderRejectedKafkaProducer,
    private val orderCancelledKafkaProducer: OrderCancelledKafkaProducer,
    private val accountBalanceChangedKafkaProducer: AccountBalanceChangedKafkaProducer,
    private val executionPersistenceService: ExecutionPersistenceService,
    private val executionBackfillServiceProvider: ObjectProvider<KisExecutionBackfillService>,
    private val externalOrderAccountResolver: ExternalOrderAccountResolver,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    private val objectMapper: ObjectMapper,
    private val accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(KisExecutionWsListener::class.java)

    companion object {
        /**
         * ADR 0084 P0-3 — race fix retry 횟수 (50ms × 3회 = 150ms max).
         * REST submit ACK 응답 latency 측정 후 backlog §62에서 운영 tune.
         */
        const val RACE_RETRY_COUNT: Int = 3
        const val RACE_RETRY_INTERVAL_MS: Long = 50L

        /**
         * ADR 0084 P0-3 — event_id 합성 규칙.
         * `{kisOrderNo}:{stckCntgHour}:{cntgQty}:{cntgPrice}` — 같은 초/같은 수량 부분체결 충돌을 줄인다.
         * Flyway V35 `uq_execution_event_id` UNIQUE partial index와 정합.
         */
        fun buildEventId(
            kisOrderNo: String,
            stckCntgHour: String,
            cntgQty: Int,
            cntgPrice: BigDecimal? = null,
        ): String =
            listOfNotNull(
                kisOrderNo,
                stckCntgHour,
                cntgQty.toString(),
                cntgPrice?.stripTrailingZeros()?.toPlainString(),
            ).joinToString(":")
    }

    /**
     * KIS WS body 진입점 — [KisExecutionMessage.parseFromCaretDelimited] 파싱 후 [processMessage] 위임.
     *
     * 본 함수는 KisWebSocketBridge에서 H0STCNI0/H0STCNI9 수신 시 호출 (Phase 2 또는 후속 합류).
     */
    fun onWebSocketMessage(rawBody: String) {
        try {
            val msg = KisExecutionMessage.parseFromCaretDelimited(rawBody)
            processMessage(msg)
        } catch (e: Exception) {
            log.error("KisExecutionWsListener parse 실패: err={} body={}", e.message, rawBody.take(200))
        }
    }

    /**
     * §14-6 흐름 분기 LOCK 적용 + ADR 0084 P0-3 race fix.
     *
     * 본 함수는 단위 테스트 진입점 — [KisExecutionMessage] 직접 주입 가능 (parser 우회).
     *
     * ### ADR 0084 P0-3 — race fix 흐름
     * 1. findByKisOrderNo == null 시 즉시 skip 대신 짧은 retry (50ms × 3회) — REST ACK 처리 race window 흡수
     * 2. retry 후에도 null이면 pre-insert 패턴 진입 ([preInsertOrderRequest]) — 후속 REST ACK이 동일 kisOrderNo update로 정합
     * 3. pre-insert 실패 시에도 graceful skip (자본 보호 — listener thread 안 죽이기 + log.error + audit)
     *
     * ### F7 — 취소 신규 ODNO 매핑 fallback (2026-06-12 라이브 incident · 최고 관리자 결재)
     * KIS는 취소(TTTC0013U) 발행을 **별도 신규 주문번호**로 채번한다. 그 취소 통보(`CNTG_YN=1 ACPT_YN=3`)가
     * WS로 돌아오면 `ODER_NO`(idx2)는 취소 신규번호이고 우리 DB에 없다. 기존 path는 즉시 pre-insert로 빠져
     * 자기 취소를 **EXTERNAL 유령 행**으로 흡수했다(11:26 incident — orderId=292 WS_PREINSERT).
     * 그러나 KIS는 같은 통보에 `OODER_NO`(idx3 = 원주문번호)를 함께 실어 보낸다. 따라서 주문/정정/취소/거부 통보
     * (`CNTG_YN=1`)에 한해 odno miss 시 [findByOriginalOrderNo] 로 원주문을 fallback 조회해 자기 주문임을 회복한다.
     * - 체결 통보(`CNTG_YN=2`)는 fallback 미적용 — 체결은 본인 ODNO 기준이라 원주문 오귀속 회귀 차단.
     * - originalOdno 도 우리 DB에 없으면 = 진짜 외부 주문(한투앱 수동) → 기존 pre-insert 흡수 경로 그대로 보존.
     */
    fun processMessage(msg: KisExecutionMessage) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.warn(
                "KisExecutionWsListener — OrderRequestRepository 미합류 (Phase 2 미완): odno={} cntgYn={}",
                msg.odno,
                msg.cntgYn,
            )
            return
        }

        // ADR 0084 P0-3 — race fix 1단계: short retry (50ms × 3회)
        // REST submit ACK 응답이 WS 통보보다 늦게 도달하는 race window 흡수.
        // F7 — null 시 취소 신규 ODNO 대비 OODER_NO fallback 시도 (CNTG_YN=1 통보 한정).
        val request =
            findOrderRequestWithRetry(orderRepo, msg.odno)
                ?: findByOriginalOrderNo(orderRepo, msg)

        if (request == null) {
            // ADR 0084 P0-3 — race fix 2단계: pre-insert 패턴
            // retry·OODER_NO fallback 모두 miss → 진짜 외부 주문 → WS 통보가 진실원 → OrderRequest stub row INSERT.
            // 후속 REST ACK 응답 시 OrderRouterKafkaListener step 8이 동일 kisOrderNo로 update 정합.
            val preInserted = preInsertOrderRequest(orderRepo, msg)
            if (preInserted == null) {
                log.warn(
                    "KisExecutionWsListener — pre-insert 실패 — graceful skip: odno={} cntgYn={}",
                    msg.odno,
                    msg.cntgYn,
                )
                return
            }
            dispatchByFlag(msg, preInserted)
            return
        }

        dispatchByFlag(msg, request)
    }

    /**
     * F7 — 취소 신규 ODNO 매핑 fallback (2026-06-12 라이브 incident fix · 최고 관리자 결재).
     *
     * KIS 취소 통보의 `ODER_NO`(취소 신규번호)는 우리 DB에 없으나 `OODER_NO`(원주문번호)는 우리가 발행한 원주문.
     * 주문/정정/취소/거부 통보(`CNTG_YN=1`)에 한해 — 체결 통보(`CNTG_YN=2`)는 본인 ODNO 기준이므로 적용 제외 —
     * odno miss 시 originalOdno 로 원주문을 조회해 자기 주문임을 회복한다.
     *
     * 반환:
     *   원주문 hit  → 그 OrderRequest (caller가 dispatchByFlag로 CANCELLED/REJECTED 정상 종결)
     *   조건 불충족·miss → null (caller가 진짜 외부 주문으로 보고 기존 pre-insert 흡수 경로 유지 — 회귀 0)
     *
     * raw 출처: examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446 (idx3 OODER_NO 원주문번호).
     */
    private fun findByOriginalOrderNo(
        orderRepo: OrderRequestRepository,
        msg: KisExecutionMessage,
    ): OrderRequest? {
        // 체결 통보(CNTG_YN=2)는 OODER_NO fallback 미적용 — 원주문 오귀속 회귀 차단.
        if (msg.cntgYn != "1") return null
        val originalOdno = msg.originalOdno.takeIf { it.isNotBlank() && it != msg.odno } ?: return null

        val original =
            try {
                orderRepo.findByKisOrderNo(originalOdno)
            } catch (e: Exception) {
                log.error(
                    "KisExecutionWsListener findByKisOrderNo(OODER_NO) 실패: odno={} oodno={} err={}",
                    msg.odno,
                    originalOdno,
                    e.message,
                )
                null
            } ?: return null

        log.info(
            "KisExecutionWsListener — 취소 신규 ODNO를 OODER_NO로 원주문 회복 (외부 흡수 차단): " +
                "cancelOdno={} originalOdno={} orderId={} acptYn={}",
            msg.odno,
            originalOdno,
            original.id,
            msg.acptYn,
        )
        return original
    }

    /**
     * ADR 0084 P0-3 — findByKisOrderNo race fix retry helper.
     * REST ACK 응답이 늦게 도달하는 race window를 흡수 (50ms × 3회 = 최대 150ms).
     *
     * 본 retry는 listener thread block을 유발하나 — KIS WS 메시지 처리는 ReactorNetty event-loop가 아닌
     * KisWebSocketBridge.handleMessage scheduler thread에서 실행되므로 wsSession latency에 영향 없음.
     * (handleMessage가 receiveFlux.doOnNext에서 호출 → Reactor 기본 publishOn이 default scheduler).
     *
     * retry 횟수 + 간격은 backlog §62로 운영 측정 후 tune.
     */
    private fun findOrderRequestWithRetry(
        orderRepo: OrderRequestRepository,
        kisOrderNo: String,
    ): OrderRequest? {
        repeat(RACE_RETRY_COUNT) { attempt ->
            val found =
                try {
                    orderRepo.findByKisOrderNo(kisOrderNo)
                } catch (e: Exception) {
                    log.error(
                        "KisExecutionWsListener findByKisOrderNo 실패: odno={} attempt={} err={}",
                        kisOrderNo,
                        attempt,
                        e.message,
                    )
                    null
                }
            if (found != null) {
                if (attempt > 0) {
                    log.info(
                        "KisExecutionWsListener race retry hit: odno={} attempt={}",
                        kisOrderNo,
                        attempt,
                    )
                }
                return found
            }
            if (attempt < RACE_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(RACE_RETRY_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    /**
     * ADR 0084 P0-3 — pre-insert 패턴 (architect 권장 §2-1) + 2026-06-05 외부 주문 흡수 user 매핑·mode·source fix.
     *
     * race fix retry 후에도 OrderRequest 부재 시 — WS 통보를 진실원으로 stub row INSERT.
     * 후속 REST ACK 응답 시 [OrderRouterKafkaListener.updateKisAck] 가 동일 kisOrderNo row를 update 갱신 (idempotency 정합).
     *
     * ### 2026-06-05 자본 path fix (라이브 실측 — 사용자 KIS HTS 직접 매수 흡수)
     * 기존 결함 3종:
     *  1. `userId = msg.custId`("thdeodns" HTS ID) → `user_account` 부재 → position upsert FK 위반.
     *  2. `mode = REAL` 하드코딩 → 모의 체결인데 REAL 기록 → ADR 0109 canary cap 오염.
     *  3. `source` 기본값 AUTO → 외부 수동 주문인데 자동매매로 분류 → 감사 혼선.
     *
     * fix: [ExternalOrderAccountResolver] 로 계좌번호(ACNT_NO) → 내부 userId·TradingMode 역조회.
     *  - Mapped   : 내부 userId + 매핑된 mode + source=EXTERNAL 로 정직 INSERT.
     *  - Unmapped : FK 폭발 회피 위해 stub INSERT 거부(null) + SystemEvent WARN (정직한 실패).
     *
     * pre-insert sentinel 매핑:
     *  - signalId = 0L · strategyId = 0L (sentinel)
     *  - userId = 매핑된 내부 userId (구 msg.custId 직접 사용 결함 제거)
     *  - code = msg.code (KIS PDNO)
     *  - side = msg.sellBuyCls "02"=BUY · "01"=SELL (raw L389~L446 정합)
     *  - priceType = MARKET (msg.odKind raw 변환 불완전 — sentinel)
     *  - quantity = msg.orderQty (cross-ref)
     *  - limitPrice = null · status = ACK (WS 통보 = KIS 접수 후 단계)
     *  - mode = 매핑된 accountType 정합 (구 REAL 하드코딩 결함 제거)
     *  - source = EXTERNAL (외부 흡수 — 구 AUTO 결함 제거)
     *  - kisOrderNo = msg.odno · idempotencyKey = "WS_PREINSERT:{odno}"
     *  - requestedAt = Instant.now (sentinel — audit trace 의미)
     *
     * **사용자 신뢰 의무**: pre-insert row는 audit에서 KIS_ACK + extra `{"source":"WS_PREINSERT","htsId":...}` 마커로 trace 가능.
     */
    private fun preInsertOrderRequest(
        orderRepo: OrderRequestRepository,
        msg: KisExecutionMessage,
    ): OrderRequest? {
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val sentinelIdempotencyKey = "WS_PREINSERT:${msg.odno}"

        // pre-insert 직전 — 동일 idempotencyKey 이미 합류했나? (재진입 멱등)
        val existing =
            try {
                orderRepo.findByIdempotencyKey(sentinelIdempotencyKey)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener preInsert findByIdempotencyKey 실패: odno={} err={}", msg.odno, e.message)
                null
            }
        if (existing != null) {
            return existing
        }

        // 2026-06-05 fix — 외부 흡수 주문 user/mode 매핑. 실패 시 FK 폭발 대신 정직한 skip.
        val mapped =
            when (val resolution = externalOrderAccountResolver.resolve(msg.acntNo, msg.custId)) {
                is ExternalOrderAccountResolver.Resolution.Mapped -> resolution
                is ExternalOrderAccountResolver.Resolution.Unmapped -> {
                    emitUnmappedExternalOrder(msg, resolution.reason)
                    log.warn(
                        "KisExecutionWsListener — 외부 주문 user 매핑 실패, pre-insert 보류 (FK 위반 회피): odno={} htsId={} reason={}",
                        msg.odno,
                        maskUserId(msg.custId),
                        resolution.reason,
                    )
                    return null
                }
            }

        val side = if (msg.sellBuyCls == "01") OrderSide.SELL else OrderSide.BUY
        val stub =
            OrderRequest(
                userId = mapped.userId,
                signalId = 0L,
                strategyId = 0L,
                code = msg.code,
                side = side,
                priceType = OrderPriceType.MARKET,
                quantity = msg.orderQty.coerceAtLeast(1),
                limitPrice = null,
                status = OrderStatus.ACK,
                mode = mapped.mode,
                kisOrderNo = msg.odno,
                idempotencyKey = sentinelIdempotencyKey,
                requestedAt = now,
                acknowledgedAt = now,
                source = OrderSource.EXTERNAL,
                // ADR 0115 §2-P2-⑥ — WS 외부 흡수 stub strategy_id=0 sentinel을 EXTERNAL 출처로 명시.
                orderTrigger = OrderTrigger.EXTERNAL,
            )

        val saved =
            try {
                orderRepo.save(stub)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener pre-insert save 실패: odno={} err={}", msg.odno, e.message)
                return null
            }

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = saved.id,
                    userId = saved.userId,
                    eventType = OrderAuditEventType.KIS_ACK,
                    payloadJson =
                        jsonOf(
                            msg,
                            extra = mapOf("source" to "WS_PREINSERT", "orderSource" to "EXTERNAL", "htsId" to msg.custId),
                        ),
                    createdAt = now,
                ),
            )
        }

        log.warn(
            "KisExecutionWsListener — WS_PREINSERT row 합류 (외부 흡수 EXTERNAL · mode={}): orderId={} odno={} user={}",
            saved.mode,
            saved.id,
            msg.odno,
            maskUserId(saved.userId),
        )
        return saved
    }

    /**
     * 외부 주문 흡수 매핑 실패 SystemEvent WARN — 정직한 실패 audit (2026-06-05 fix).
     *
     * graceful: SystemEventLogger 미합류·emit 실패가 listener thread cascade 차단 방지 (runCatching).
     */
    private fun emitUnmappedExternalOrder(
        msg: KisExecutionMessage,
        reason: String,
    ) {
        val logger = systemEventLoggerProvider.getIfAvailable() ?: return
        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = SystemEventSeverity.WARN,
                    actor = "KisExecutionWsListener",
                    action = "EXTERNAL_ORDER_UNMAPPED",
                    userId = null,
                    detailJson =
                        objectMapper.writeValueAsString(
                            mapOf(
                                "odno" to msg.odno,
                                "acntNo" to msg.acntNo,
                                "code" to msg.code,
                                "reason" to reason,
                            ),
                        ),
                ),
            )
        }.onFailure { e ->
            log.error("KisExecutionWsListener — EXTERNAL_ORDER_UNMAPPED SystemEvent emit 실패: odno={} err={}", msg.odno, e.message)
        }
    }

    private fun dispatchByFlag(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        when {
            msg.cntgYn == "1" && msg.rfusYn == "Y" -> handleRejected(msg, request)
            msg.cntgYn == "1" && msg.acptYn == "1" -> handleAckBackup(msg, request)
            msg.cntgYn == "1" && msg.acptYn == "3" -> handleCancelled(msg, request)
            msg.cntgYn == "2" -> handleExecution(msg, request)
            else -> {
                log.debug(
                    "KisExecutionWsListener — 미분류 (skip): odno={} cntgYn={} acptYn={} rfusYn={}",
                    msg.odno,
                    msg.cntgYn,
                    msg.acptYn,
                    msg.rfusYn,
                )
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private fun invalidateAccountSnapshot(
        order: OrderRequest,
        reason: String,
        kisOrderNo: String?,
    ) {
        accountSnapshotCoordinatorProvider
            ?.getIfAvailable()
            ?.invalidate(
                userId = order.userId,
                mode = order.mode,
                reason = reason,
                triggerKisOrderNo = kisOrderNo,
            )
    }

    /**
     * P2-4-3 진보 페르소나 가드 (ADR 0087, 2026-05-19).
     *
     * 영속된 [OrderRequest.id]는 invariant상 non-null. null이면:
     *  - JPA save 후 generated key 미설정 (영속성 결함)
     *  - pre-insert path stub row 생성 실패 후 in-memory copy 누락
     *  - 또는 caller가 잘못된 (영속 안 된) request 전달
     *
     * 어느 경로든 자본 path 실행 불가 — 명시 [requireNotNull] throw + handler 이름 메시지로
     * 운영자 audit 시 즉시 root cause 매칭 가능 (vs raw `!!` KotlinNullPointerException).
     */
    private fun requireOrderId(
        request: OrderRequest,
        handlerName: String,
    ): Long =
        requireNotNull(request.id) {
            "KisExecutionWsListener.$handlerName — request.id null (자본 path invariant 위반): " +
                "kisOrderNo=${request.kisOrderNo} userId=${maskUserId(request.userId)}"
        }

    private fun handleRejected(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val reason = "WS_REJECTED:${msg.acptYn}"

        // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드: 영속된 request.id는 invariant상 non-null.
        // null이면 OrderRequest persistence 또는 pre-insert 로직 결함 — 명시 throw로 운영자 즉시 매칭.
        val requestId = requireOrderId(request, "handleRejected")

        val updated =
            try {
                orderRepo.markCompleted(requestId, now, OrderStatus.REJECTED)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener markCompleted REJECTED 실패: orderId={} err={}", requestId, e.message)
                request.copy(status = OrderStatus.REJECTED, rejectReason = reason, completedAt = now)
            } ?: request.copy(status = OrderStatus.REJECTED, rejectReason = reason, completedAt = now)

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = requestId,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_REJECT,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }

        invalidateAccountSnapshot(updated, reason = "WS_REJECTED", kisOrderNo = msg.odno)
        orderRejectedKafkaProducer.publish(updated, reason)
        log.warn("KisExecutionWsListener REJECTED: orderId={} odno={}", requestId, msg.odno)
    }

    private fun handleAckBackup(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        // §14-6: 주문 접수 통보 (§14-2 step 8 KIS_ACK의 백업 — 중복 시 멱등)
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_ACK,
                    payloadJson = jsonOf(msg, extra = mapOf("source" to "WS_BACKUP")),
                    createdAt = now,
                ),
            )
        }
        log.debug("KisExecutionWsListener ACK 백업: orderId={} odno={}", request.id, msg.odno)
    }

    private fun handleCancelled(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)

        // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드 (handleRejected 정합)
        val requestId = requireOrderId(request, "handleCancelled")

        val updated =
            try {
                orderRepo.markCompleted(requestId, now, OrderStatus.CANCELLED)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener markCompleted CANCELLED 실패: orderId={} err={}", requestId, e.message)
                request.copy(status = OrderStatus.CANCELLED, completedAt = now)
            } ?: request.copy(status = OrderStatus.CANCELLED, completedAt = now)

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = requestId,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_ACK,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }
        orderCancelledKafkaProducer.publish(updated)
        invalidateAccountSnapshot(updated, reason = "WS_CANCELLED", kisOrderNo = msg.odno)
        // Polling → STOMP push (2026-05-20) — 잔고 변경 trigger (취소도 예약 cash 회복).
        accountBalanceChangedKafkaProducer.publish(
            userId = updated.userId,
            mode = updated.mode,
            reason = AccountBalanceChangedKafkaProducer.REASON_CANCELLED,
            triggerKisOrderNo = msg.odno,
        )
        log.info("KisExecutionWsListener CANCELLED: orderId={} odno={}", requestId, msg.odno)
    }

    private fun handleExecution(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val execRepo = orderExecutionRepositoryProvider.getIfAvailable()
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val executedAt = parseExecutedAt(msg.stckCntgHour)

        // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드 (handleRejected·handleCancelled 정합)
        // handleExecution 4 site (line 458·494·504·533 기존) 일괄 흡수.
        val requestId = requireOrderId(request, "handleExecution")

        if (execRepo == null) {
            log.warn(
                "KisExecutionWsListener — OrderExecutionRepository 미합류: orderId={} odno={}",
                requestId,
                msg.odno,
            )
            return
        }

        // ADR 0084 P0-3 — event_id 멱등 가드 (WS + REST backfill 양 경로 dedup LOCK)
        val eventId = buildEventId(msg.odno, msg.stckCntgHour, msg.cntgQty, msg.cntgUnpr)
        val alreadyPersisted =
            try {
                execRepo.existsByEventId(eventId)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener existsByEventId 실패 — fail-open INSERT 진입: eventId={} err={}", eventId, e.message)
                false
            }
        if (alreadyPersisted) {
            handleDuplicateExecutionEvent(request, requestId, eventId, execRepo)
            log.info(
                "KisExecutionWsListener — 중복 체결 skip (event_id dedup): orderId={} eventId={}",
                requestId,
                eventId,
            )
            return
        }

        // 1. order_execution INSERT (eventId 포함)
        val execution =
            try {
                executionPersistenceService.saveAndApplyPosition(
                    request,
                    OrderExecution(
                        orderId = requestId,
                        userId = request.userId,
                        kisOrderNo = msg.odno,
                        executedQuantity = msg.cntgQty,
                        executedPrice = msg.cntgUnpr,
                        executedAt = executedAt,
                        eventId = eventId,
                        createdAt = now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "KisExecutionWsListener execution persistence 실패: orderId={} eventId={} err={}",
                    requestId,
                    eventId,
                    e.message,
                )
                return
            }

        // 2. audit FILL_RECEIVED
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = requestId,
                    userId = request.userId,
                    eventType = OrderAuditEventType.FILL_RECEIVED,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }

        // 3. 누적 합 평가 → PARTIALLY_FILLED / FILLED / OVERFLOW
        val sum =
            try {
                execRepo.sumExecutedQuantityByOrder(requestId)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener sumExecutedQuantityByOrder 실패: orderId={} err={}", requestId, e.message)
                msg.cntgQty
            }

        when {
            sum < request.quantity -> {
                val partial =
                    try {
                        orderRepo.updateStatus(requestId, OrderStatus.PARTIALLY_FILLED, null)
                    } catch (e: Exception) {
                        log.error("updateStatus PARTIALLY_FILLED 실패: orderId={} err={}", requestId, e.message)
                        request.copy(status = OrderStatus.PARTIALLY_FILLED)
                    } ?: request.copy(status = OrderStatus.PARTIALLY_FILLED)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = requestId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.PARTIAL_FILL,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                orderFilledKafkaProducer.publish(partial, execution, partial = true)
                invalidateAccountSnapshot(partial, reason = "WS_PARTIALLY_FILLED", kisOrderNo = msg.odno)
                // Polling → STOMP push (2026-05-20) — 부분 체결도 잔고 변경 (현금·평가금액 즉시 변동).
                accountBalanceChangedKafkaProducer.publish(
                    userId = partial.userId,
                    mode = partial.mode,
                    reason = AccountBalanceChangedKafkaProducer.REASON_FILLED,
                    triggerKisOrderNo = msg.odno,
                )
                log.info(
                    "KisExecutionWsListener PARTIALLY_FILLED: orderId={} sum={}/{}",
                    requestId,
                    sum,
                    request.quantity,
                )
            }

            sum == request.quantity -> {
                val filled =
                    try {
                        orderRepo.markCompleted(requestId, now, OrderStatus.FILLED)
                    } catch (e: Exception) {
                        log.error("markCompleted FILLED 실패: orderId={} err={}", requestId, e.message)
                        request.copy(status = OrderStatus.FILLED, completedAt = now)
                    } ?: request.copy(status = OrderStatus.FILLED, completedAt = now)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = requestId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.FULL_FILL,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                orderFilledKafkaProducer.publish(filled, execution, partial = false)
                invalidateAccountSnapshot(filled, reason = "WS_FILLED", kisOrderNo = msg.odno)
                // Polling → STOMP push (2026-05-20) — 전체 체결 → 잔고 즉시 invalidate trigger.
                accountBalanceChangedKafkaProducer.publish(
                    userId = filled.userId,
                    mode = filled.mode,
                    reason = AccountBalanceChangedKafkaProducer.REASON_FILLED,
                    triggerKisOrderNo = msg.odno,
                )
                log.info("KisExecutionWsListener FILLED: orderId={} qty={}", requestId, sum)
            }

            else -> {
                // §14-4 risk 매트릭스: executions 누적 합 > order.quantity
                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = requestId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.EXECUTION_OVERFLOW,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                log.error(
                    "KisExecutionWsListener EXECUTION_OVERFLOW: orderId={} sum={} > qty={}",
                    requestId,
                    sum,
                    request.quantity,
                )
            }
        }
    }

    private fun handleDuplicateExecutionEvent(
        request: OrderRequest,
        requestId: Long,
        eventId: String,
        execRepo: OrderExecutionRepository,
    ) {
        val currentExecutedQty =
            try {
                execRepo.sumExecutedQuantityByOrder(requestId)
            } catch (e: Exception) {
                log.warn(
                    "KisExecutionWsListener duplicate event sum 조회 실패 — REST sync 생략: orderId={} eventId={} err={}",
                    requestId,
                    eventId,
                    e.message,
                )
                return
            }
        if (currentExecutedQty >= request.quantity) return

        val service = executionBackfillServiceProvider.getIfAvailable()
        if (service == null) {
            log.warn(
                "KisExecutionWsListener duplicate event on open order — backfill service 미합류: orderId={} eventId={} sum={}/{}",
                requestId,
                eventId,
                currentExecutedQty,
                request.quantity,
            )
            return
        }

        val accountType =
            if (request.mode == TradingMode.REAL) {
                AccountType.REAL
            } else {
                AccountType.MOCK
            }
        log.warn(
            "KisExecutionWsListener duplicate event on open order — KIS REST execution sync 예약: orderId={} eventId={} sum={}/{}",
            requestId,
            eventId,
            currentExecutedQty,
            request.quantity,
        )
        Schedulers.boundedElastic().schedule {
            val result =
                runCatching {
                    service.syncUserTodayExecutions(request.userId, accountType)
                }.onFailure { e ->
                    log.error(
                        "KisExecutionWsListener duplicate event REST sync 실패: orderId={} eventId={} err={}",
                        requestId,
                        eventId,
                        e.message,
                    )
                }.getOrNull()

            if (result != null && !result.isComplete) {
                log.warn(
                    "KisExecutionWsListener duplicate event REST sync 불완전: orderId={} eventId={} reason={}",
                    requestId,
                    eventId,
                    result.reasonCode,
                )
            }
        }
    }

    /**
     * STCK_CNTG_HOUR (HHMMSS) + today (KST) → Instant 합성.
     */
    internal fun parseExecutedAt(hhmmss: String): Instant {
        val time =
            try {
                LocalTime.parse(hhmmss.padStart(6, '0'), DateTimeFormatter.ofPattern("HHmmss"))
            } catch (e: Exception) {
                log.warn("parseExecutedAt HHMMSS 파싱 실패 — 현재시각 fallback: hhmmss={} err={}", hhmmss, e.message)
                return Instant.now(clock)
            }
        val today = LocalDate.now(clock)
        return today.atTime(time).atZone(ZoneId.of("Asia/Seoul")).toInstant()
    }

    private fun jsonOf(
        msg: KisExecutionMessage,
        extra: Map<String, Any?> = emptyMap(),
    ): String =
        try {
            objectMapper.writeValueAsString(
                mapOf(
                    "odno" to msg.odno,
                    "code" to msg.code,
                    "cntgYn" to msg.cntgYn,
                    "acptYn" to msg.acptYn,
                    "rfusYn" to msg.rfusYn,
                    "cntgQty" to msg.cntgQty,
                    "cntgUnpr" to msg.cntgUnpr.toPlainString(),
                    "stckCntgHour" to msg.stckCntgHour,
                ) + extra,
            )
        } catch (e: Exception) {
            "{\"error\":\"audit json failed: ${e.message}\"}"
        }
}
