package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderInquireDailyCcldPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderInquireResult
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * PENDING_RECONCILE 상태 주문 검증 worker (ADR 0084 §2-2 P0-2 — 외부 코드 리뷰 결함 #3 fix).
 *
 * ### 배경
 * OrderRouterKafkaListener `submitWithRetry`가 KIS REST 5xx 재시도 후 실패 또는 socket timeout 검출 시
 * REJECTED 대신 PENDING_RECONCILE 상태로 마킹. 본 worker가 1분 cron으로 KIS inquire-daily-ccld 호출 →
 * 실제 KIS 상태 검증 → 최종 status 전이 (ACK / REJECTED / EXPIRED).
 *
 * ### cron 1분 (fixedDelay 60_000ms)
 * - fixedDelay: 직전 호출 완료 시점부터 60s — KIS rate limit 초과 회피 (ADR 0037 정합)
 * - cron 본문은 PENDING_RECONCILE 0건이면 즉시 종료 → 평상시 부담 0
 *
 * ### 분기 매트릭스
 *
 * | inquire-daily-ccld 결과 | OrderStatus 전이 | audit event |
 * |---|---|---|
 * | [OrderInquireResult.Found] | PENDING_RECONCILE → ACK (체결 0건) 또는 FILLED (체결 = 주문수량) | KIS_RECONCILE_RESOLVED |
 * | [OrderInquireResult.NotFound] | PENDING_RECONCILE → REJECTED (KIS 미접수 확정) | KIS_RECONCILE_RESOLVED |
 * | [OrderInquireResult.Failed] | 그대로 PENDING_RECONCILE (다음 tick 재시도) | (audit 미발행) |
 * | requestedAt < now - 15분 | PENDING_RECONCILE → EXPIRED (운영자 수동 검증 필요) | KIS_RECONCILE_TIMEOUT |
 *
 * ### grace period (5분)
 * `requestedAt + 5분 < now` 인 row만 lookup — 너무 최근 row는 KIS 인덱스 미반영 위험 회피.
 *
 * ### KIS-first raw 인용 (헌법 §3 + ADR 0084 §2-2)
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
 * - tr_id: TTTC0081R (실전) / VTTC0081R (모의) — 3개월 이내 (raw L106~L121)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderReconciliationWorker(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val kisOrderInquireDailyCcldPort: KisOrderInquireDailyCcldPort,
    private val objectMapper: ObjectMapper,
    private val systemEventLogger: SystemEventLogger,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(OrderReconciliationWorker::class.java)

    companion object {
        /** grace period — requestedAt + 5분 이상 경과한 row만 lookup. */
        internal val GRACE_PERIOD: Duration = Duration.ofMinutes(5)

        /** EXPIRED 분기 — requestedAt + 15분 경과 시 KIS 결정 불가로 운영자 위임. */
        internal val EXPIRY_THRESHOLD: Duration = Duration.ofMinutes(15)

        /** 한 cron tick에서 처리할 row 수 — KIS rate limit 보호. */
        internal const val BATCH_LIMIT: Int = 50

        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** SystemEvent actor — P1 catalog (spec §6.1) */
        private const val SYSTEM_EVENT_ACTOR = "OrderReconciliationWorker"
    }

    /**
     * 1분 cron — fixedDelay 60_000ms.
     *
     * application.yml `magicjar.order.reconcile.enabled=false` toggle 미합류 — 본 baseline은 default 활성.
     * 비활성 필요 시 ADR 0084 rollback 매트릭스 정합 toggle 도입.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun reconcileTick() {
        val repo = orderRequestRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn("OrderReconciliationWorker — OrderRequestRepository 미합류 (Phase 2 미완) — skip")
            return
        }

        val now = Instant.now(clock)
        val cutoff = now.minus(GRACE_PERIOD)
        val pending =
            try {
                repo.findByStatusAndRequestedAtBefore(OrderStatus.PENDING_RECONCILE, cutoff, BATCH_LIMIT)
            } catch (e: Exception) {
                log.error("PENDING_RECONCILE lookup 실패: err={}", e.message, e)
                return
            }

        if (pending.isEmpty()) {
            log.debug("OrderReconciliationWorker — PENDING_RECONCILE 0건 (cron skip)")
            return
        }

        log.info(
            "OrderReconciliationWorker tick — pendingCount={} cutoff={} batchLimit={}",
            pending.size,
            cutoff,
            BATCH_LIMIT,
        )

        // ★ SystemEvent emit point #1 (P1 spec §6.1) — RECONCILE_FALLBACK_FIRED
        // PENDING_RECONCILE 1건 이상 발견 시 운영 audit (INFO severity).
        emitSystemEvent(
            severity = SystemEventSeverity.INFO,
            action = "RECONCILE_FALLBACK_FIRED",
            userId = null, // system-wide audit — 다중 사용자 row 합산 (개별 사용자 audit은 RESOLVED에서)
            detailJson = """{"pendingCount":${pending.size}}""",
        )

        for (row in pending) {
            try {
                processRow(repo, row, now)
            } catch (e: Exception) {
                log.error(
                    "OrderReconciliationWorker.processRow 실패 (다음 tick 재시도): orderId={} userId={} err={}",
                    row.id,
                    row.userId,
                    e.message,
                    e,
                )
            }
        }
    }

    /**
     * 단일 row 처리 분기 — kisOrderNo 부재 시 EXPIRED, KIS 검증 결과별 status 전이 + audit.
     */
    internal fun processRow(
        repo: OrderRequestRepository,
        row: OrderRequest,
        now: Instant,
    ) {
        val orderId = row.id ?: return
        val elapsed = Duration.between(row.requestedAt, now)

        // ADR 0084 P0-2 — 15분 timeout 시 EXPIRED (운영자 수동 검증 위임).
        if (elapsed > EXPIRY_THRESHOLD) {
            log.warn(
                "OrderReconciliationWorker EXPIRED (15분 timeout — 운영자 수동 검증 필요): orderId={} userId={} elapsed={}",
                orderId,
                row.userId,
                elapsed,
            )
            repo.markCompleted(orderId, now, OrderStatus.EXPIRED)
            writeAudit(
                orderId = orderId,
                userId = row.userId,
                eventType = OrderAuditEventType.KIS_RECONCILE_TIMEOUT,
                payload =
                    mapOf(
                        "elapsedSeconds" to elapsed.seconds,
                        "requestedAt" to row.requestedAt.toString(),
                        "rejectReason" to row.rejectReason,
                    ),
            )
            return
        }

        // kisOrderNo 부재 시 — submitWithRetry 가 ACK 직전에 5xx/timeout 만난 케이스. ODNO 0건이면 NotFound 동치.
        // KIS inquire-daily-ccld는 ODNO 필터 가능 — kisOrderNo가 있는 경우 단건 검색, 없으면 EXPIRED 분기.
        val kisOrderNo = row.kisOrderNo
        if (kisOrderNo.isNullOrBlank()) {
            // ACK 미수신 + ODNO 미발급 → KIS 미접수 가능성 높음. 그러나 자본 audit 보호를 위해
            // 단번 REJECTED 마킹은 위험 — EXPIRED 보류 + 운영자 검증 (timeout 진입 이전이라도 ODNO 부재는 ambiguous).
            log.warn(
                "OrderReconciliationWorker — kisOrderNo 부재 PENDING_RECONCILE row (운영자 검증 위임): orderId={} userId={} elapsed={}",
                orderId,
                row.userId,
                elapsed,
            )
            // ODNO 부재인 경우 본 tick은 skip하고 다음 tick에서도 동일 분기 — 15분 후 EXPIRED 진입.
            return
        }

        // KIS inquire-daily-ccld 호출 — 당일 + 전영업일까지 window (당일 미반영 회피).
        val today = LocalDate.now(KST)
        val from = today.minusDays(1)
        val result =
            kisOrderInquireDailyCcldPort.inquireByOrderNo(
                userId = row.userId,
                mode = row.mode,
                kisOrderNo = kisOrderNo,
                inquireStartDate = from,
                inquireEndDate = today,
            )

        when (result) {
            is OrderInquireResult.Found -> {
                // KIS에 주문 존재 — 체결 수량으로 ACK·FILLED 분기.
                val finalStatus =
                    when {
                        result.filledQty >= result.orderQty && result.orderQty > 0 -> OrderStatus.FILLED
                        result.filledQty > 0 -> OrderStatus.PARTIALLY_FILLED
                        else -> OrderStatus.ACK
                    }
                log.info(
                    "OrderReconciliationWorker RESOLVED Found: orderId={} userId={} ODNO={} filledQty={} orderQty={} → status={}",
                    orderId,
                    row.userId,
                    result.odno,
                    result.filledQty,
                    result.orderQty,
                    finalStatus,
                )
                // ACK 상태로 1차 갱신 (kisOrderNo·acknowledgedAt set) + FILLED 등은 markCompleted로 이어 갱신.
                repo.updateKisAck(
                    id = orderId,
                    kisOrderNo = result.odno,
                    kisFwdgOrgno = result.krxFwdgOrdOrgno,
                    acknowledgedAt = now,
                )
                if (finalStatus != OrderStatus.ACK) {
                    repo.markCompleted(orderId, now, finalStatus)
                }
                writeAudit(
                    orderId = orderId,
                    userId = row.userId,
                    eventType = OrderAuditEventType.KIS_RECONCILE_RESOLVED,
                    payload =
                        mapOf(
                            "resolution" to "FOUND",
                            "finalStatus" to finalStatus.name,
                            "odno" to result.odno,
                            "filledQty" to result.filledQty,
                            "orderQty" to result.orderQty,
                            "ccldStatus" to result.ccldStatus,
                        ),
                )

                // ★ SystemEvent emit point #2 (P1 spec §6.1) — RECONCILE_FALLBACK_RESOLVED
                // filledQty > 0 (실제 체결 발견 — 자본 audit 의무). WARN severity (운영 결함 신호).
                if (result.filledQty > 0) {
                    emitSystemEvent(
                        severity = SystemEventSeverity.WARN,
                        action = "RECONCILE_FALLBACK_RESOLVED",
                        userId = row.userId,
                        detailJson =
                            """{"orderId":$orderId,"filledQty":${result.filledQty},"orderQty":${result.orderQty}}""",
                    )
                }
            }
            is OrderInquireResult.NotFound -> {
                // KIS 응답 정상 + ODNO 미존재 — KIS 미접수 확정. REJECTED 전이.
                log.info(
                    "OrderReconciliationWorker RESOLVED NotFound (KIS 미접수 확정): orderId={} userId={} ODNO={}",
                    orderId,
                    row.userId,
                    kisOrderNo,
                )
                repo.markCompleted(orderId, now, OrderStatus.REJECTED)
                writeAudit(
                    orderId = orderId,
                    userId = row.userId,
                    eventType = OrderAuditEventType.KIS_RECONCILE_RESOLVED,
                    payload =
                        mapOf(
                            "resolution" to "NOT_FOUND",
                            "finalStatus" to OrderStatus.REJECTED.name,
                            "odno" to kisOrderNo,
                        ),
                )
            }
            is OrderInquireResult.Failed -> {
                // KIS 응답 자체 실패 (5xx·timeout·parse) — 다음 cron tick 재시도. 15분 timeout 시 EXPIRED.
                log.warn(
                    "OrderReconciliationWorker Failed (다음 tick 재시도): orderId={} userId={} ODNO={} cause={}",
                    orderId,
                    row.userId,
                    kisOrderNo,
                    result.cause,
                )
                // audit 미발행 — 노이즈 회피. EXPIRED 진입 시 KIS_RECONCILE_TIMEOUT audit으로 추적.
            }
        }
    }

    private fun writeAudit(
        orderId: Long?,
        userId: String,
        eventType: OrderAuditEventType,
        payload: Map<String, Any?>,
    ) {
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        if (auditRepo == null) {
            log.warn(
                "OrderReconciliationWorker writeAudit skip — OrderAuditEventRepository 미합류 (Phase 2 미완): orderId={} eventType={}",
                orderId,
                eventType,
            )
            return
        }
        try {
            auditRepo.save(
                OrderAuditEvent(
                    orderId = orderId,
                    userId = userId,
                    eventType = eventType,
                    payloadJson = objectMapper.writeValueAsString(payload),
                    createdAt = Instant.now(clock),
                ),
            )
        } catch (e: Exception) {
            log.error("writeAudit 실패: orderId={} eventType={} err={}", orderId, eventType, e.message)
        }
    }

    /**
     * SystemEvent emit graceful wrap (spec §6.1 Reconcile catalog).
     *
     * runCatching 의무 — emit 자체 실패가 reconcile cron tick을 cascade 차단하면 안 됨.
     * SystemEventLoggerAdapter 자체도 내부 graceful이지만, port interface 계약 (spec §6.1)에
     * 따라 caller 측도 추가 wrap 의무.
     */
    private fun emitSystemEvent(
        severity: SystemEventSeverity,
        action: String,
        userId: String?,
        detailJson: String,
    ) {
        runCatching {
            systemEventLogger.emit(
                SystemEvent(
                    category = SystemEventCategory.RECONCILE,
                    severity = severity,
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
