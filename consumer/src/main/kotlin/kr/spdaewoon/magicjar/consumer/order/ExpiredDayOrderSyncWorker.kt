package kr.spdaewoon.magicjar.consumer.order

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
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 만료 일중(day) 주문 동기화 worker — canary D2 hotfix #1 (오늘 09:01~09:04 incident 근본 fix).
 *
 * ## 결함 (이 hotfix 가 닫는 것)
 * KIS 당일 LIMIT 주문(예: 익절 SELL)은 장 마감으로 **만료**되지만, 클라이언트는 만료 통보를 못 받아
 * DB `order_request.status` 가 `ACK`(또는 `REQUESTED`)로 **영구 잔존**한다. 다음 날 매도 시그널이 들어오면
 * [kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener.publishSell] 의
 * "SELL 익절 LIMIT skip (이미 정합 auto-tp open)" 분기가 그 잔존 ACK 를 살아있는 익절 선주문으로 **오판**해
 * 실제 매도를 영원히 skip 한다(오늘 incident — 수동 정정으로 해소). 잔존 ACK 가 [RestingSellOrderCanceller]
 * 선취소 대상도 아니어서(KIS 에는 이미 없음) 청산이 마비된다.
 *
 * ## 보정 (신규 로직 최소 — 기존 컴포넌트 재사용)
 * 전일 이전(`requestedAt < 오늘 0시 KST`) 의 미종결(ACK·REQUESTED) day-order 를 발견하면 KIS 일별체결조회
 * ([KisOrderInquireDailyCcldPort.inquireByOrderNo] — [KisExecutionBackfillService] 와 동일 KIS-first 출처)로 대조해:
 *  - 전량 체결(Found, filled ≥ orderQty) → 그대로 FILLED 동기화(누락 체결 흡수는 별 worker 책임이라 여기선 status 만).
 *  - 부분/미체결(Found, filled < orderQty) 또는 미접수(NotFound) → 잔량 만료 → **CANCELLED + 사유** 동기화.
 *  - KIS 응답 실패(Failed) → 다음 tick 재시도(상태 불변).
 *
 * day-order 만료 동기화는 실돈 여부와 무관(조회·상태 보정만)하므로 MODE 분기 없이 모든 모드 동작.
 *
 * ## 실행 시점 (장 마감 후/개장 전)
 * fixedDelay cron 30분 — 만료는 장 마감(15:30) 이후 발생하고 다음 개장(09:00) 전까지 정리되면 충분하므로
 * 빈번할 필요 없다(KIS rate limit 보호). 장마감+grace 전에는 전일 이전 주문만, 이후에는 당일 장마감 전
 * 미종결 주문까지 KIS 대조한다. 미종결 0건이면 즉시 종료(평상시 부담 0).
 *
 * ## ENV
 * `magicjar.order.day-order-expiry.enabled`(default true) — 본 hotfix 가 도입하는 유일한 신규 토글.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4 + ADR 0084 P0-2 정합)
 */
@Component
class ExpiredDayOrderSyncWorker(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val kisOrderInquireDailyCcldPort: KisOrderInquireDailyCcldPort,
    private val systemEventLogger: SystemEventLogger,
    @Value("\${magicjar.order.day-order-expiry.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.order.day-order-expiry.market-close-grace-minutes:20}")
    private val marketCloseGraceMinutes: Long = 20,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(ExpiredDayOrderSyncWorker::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** 미종결 day-order 추적 상태 — KIS 가 만료시켰을 수 있으나 DB 가 살아있는 상태. */
        internal val STALE_STATUSES = listOf(OrderStatus.ACK, OrderStatus.REQUESTED)

        /** 한 tick 처리 상한 — KIS rate limit 보호. */
        internal const val BATCH_LIMIT = 50

        /** 정규장 day-order 만료 기준. grace 이후에는 당일 장마감 전 주문도 만료 대조 대상이다. */
        private val REGULAR_MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)

        private const val SYSTEM_EVENT_ACTOR = "ExpiredDayOrderSyncWorker"
        private const val EXPIRE_REASON = "DAY_ORDER_EXPIRED:day-order 장마감 만료 (KIS 일별체결 대조)"
    }

    /**
     * 30분 cron (fixedDelay). 장 마감 후 누적된 전일 만료 day-order 를 다음 개장 전까지 정리한다.
     * initialDelay 로 부팅 직후 부하 회피.
     */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 120_000)
    fun expireTick() {
        if (!enabled) {
            log.debug("ExpiredDayOrderSyncWorker — 비활성(magicjar.order.day-order-expiry.enabled=false) skip")
            return
        }
        val repo = orderRequestRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn("ExpiredDayOrderSyncWorker — OrderRequestRepository 미합류 skip")
            return
        }

        val now = Instant.now(clock)
        val staleCutoff = staleRequestedAtCutoff(now)

        val stale =
            STALE_STATUSES.flatMap { status ->
                runCatching { repo.findByStatusAndRequestedAtBefore(status, staleCutoff, BATCH_LIMIT) }
                    .onFailure { e -> log.warn("미종결 day-order lookup 실패: status={} err={}", status, e.message) }
                    .getOrDefault(emptyList())
            }
        if (stale.isEmpty()) {
            log.debug("ExpiredDayOrderSyncWorker — 미종결 day-order 0건 (cron skip): cutoff={}", staleCutoff)
            return
        }

        log.warn(
            "ExpiredDayOrderSyncWorker tick — 미종결 day-order {}건 KIS 대조(만료 동기화): cutoff={}",
            stale.size,
            staleCutoff,
        )
        emitSystemEvent(
            severity = SystemEventSeverity.INFO,
            action = "DAY_ORDER_EXPIRY_SCAN",
            userId = null,
            detailJson = """{"staleCount":${stale.size}}""",
        )

        for (row in stale) {
            try {
                processRow(repo, row, now)
            } catch (e: Exception) {
                log.error(
                    "ExpiredDayOrderSyncWorker.processRow 실패(다음 tick 재시도): orderId={} userId={} err={}",
                    row.id,
                    row.userId,
                    e.message,
                    e,
                )
            }
        }
    }

    /**
     * 단일 전일 미종결 row 처리 — KIS 일별체결 대조 후 FILLED/CANCELLED 동기화.
     */
    internal fun processRow(
        repo: OrderRequestRepository,
        row: OrderRequest,
        now: Instant,
    ) {
        val orderId = row.id ?: return
        val kisOrderNo = row.kisOrderNo
        if (kisOrderNo.isNullOrBlank()) {
            // ODNO 미발급 전일 주문 — KIS 미접수일 가능성 높지만 단정 위험. 만료 사유로 CANCELLED(잔존 ACK 제거가 본 hotfix 목적).
            log.warn("전일 미종결 day-order — kisOrderNo 부재, 만료 CANCELLED: orderId={} userId={}", orderId, row.userId)
            expire(repo, row, now, resolution = "NO_KIS_ORDER_NO")
            return
        }

        // 전일~당일 window 로 KIS 일별체결 조회(전일 발주분 포함).
        val today = LocalDate.now(clock.withZone(KST))
        val result =
            kisOrderInquireDailyCcldPort.inquireByOrderNo(
                userId = row.userId,
                mode = row.mode,
                kisOrderNo = kisOrderNo,
                inquireStartDate = today.minusDays(2),
                inquireEndDate = today,
            )

        when (result) {
            is OrderInquireResult.Found -> {
                if (result.filledQty >= result.orderQty && result.orderQty > 0) {
                    // 전량 체결됐는데 DB 가 ACK 잔존 — FILLED 동기화(체결 row 흡수는 backfill worker 책임, 여기선 status 종결).
                    log.warn(
                        "day-order 전량 체결 확인 — FILLED 동기화: orderId={} userId={} ODNO={} filled={}/{}",
                        orderId,
                        row.userId,
                        kisOrderNo,
                        result.filledQty,
                        result.orderQty,
                    )
                    repo.markCompleted(orderId, now, OrderStatus.FILLED)
                    writeAudit(orderId, row.userId, OrderAuditEventType.KIS_RECONCILE_RESOLVED, "FOUND_FILLED", result.filledQty)
                } else {
                    // 부분/미체결 — 장 마감으로 잔량 만료. CANCELLED 동기화(잔존 ACK 제거).
                    log.warn(
                        "day-order 부분/미체결 만료 — CANCELLED 동기화: orderId={} userId={} ODNO={} filled={}/{}",
                        orderId,
                        row.userId,
                        kisOrderNo,
                        result.filledQty,
                        result.orderQty,
                    )
                    expire(repo, row, now, resolution = "FOUND_UNFILLED:filled=${result.filledQty}/${result.orderQty}")
                }
            }
            is OrderInquireResult.NotFound -> {
                log.warn(
                    "day-order KIS 미존재 — 만료 CANCELLED 동기화: orderId={} userId={} ODNO={}",
                    orderId,
                    row.userId,
                    kisOrderNo,
                )
                expire(repo, row, now, resolution = "NOT_FOUND")
            }
            is OrderInquireResult.Failed -> {
                // KIS 응답 실패 — 다음 tick 재시도(상태 불변, 잘못된 CANCELLED 회피).
                log.warn(
                    "day-order KIS 조회 실패(다음 tick 재시도): orderId={} userId={} ODNO={} cause={}",
                    orderId,
                    row.userId,
                    kisOrderNo,
                    result.cause,
                )
            }
        }
    }

    /**
     * 조회 cutoff:
     * - 장마감 grace 전: 오늘 0시 KST 이전만(기존 전일 잔류 정리).
     * - 장마감 grace 이후: 오늘 정규장 day-order도 만료됐으므로 15:30+grace 이전 요청까지 정리.
     */
    private fun staleRequestedAtCutoff(now: Instant): Instant {
        val nowKst = now.atZone(KST)
        val today = nowKst.toLocalDate()
        val todayStartKst = today.atStartOfDay(KST).toInstant()
        val sameDayExpiryCutoff =
            today
                .atTime(REGULAR_MARKET_CLOSE)
                .plusMinutes(marketCloseGraceMinutes.coerceAtLeast(0))
                .atZone(KST)
                .toInstant()
        return if (now.isBefore(sameDayExpiryCutoff)) todayStartKst else sameDayExpiryCutoff
    }

    private fun expire(
        repo: OrderRequestRepository,
        row: OrderRequest,
        now: Instant,
        resolution: String,
    ) {
        val orderId = row.id ?: return
        // updateStatus 로 사유 기록 + markCompleted 로 completedAt 종결(기존 port 재사용 — 신규 fun 0, 헌법 §6 함정 회피).
        repo.updateStatus(orderId, OrderStatus.CANCELLED, EXPIRE_REASON)
        repo.markCompleted(orderId, now, OrderStatus.CANCELLED)
        writeAudit(orderId, row.userId, OrderAuditEventType.KIS_RECONCILE_RESOLVED, resolution, 0)
        emitSystemEvent(
            severity = SystemEventSeverity.WARN,
            action = "DAY_ORDER_EXPIRED",
            userId = row.userId,
            detailJson = """{"orderId":$orderId,"code":"${row.code}","resolution":"$resolution"}""",
        )
    }

    private fun writeAudit(
        orderId: Long,
        userId: String,
        eventType: OrderAuditEventType,
        resolution: String,
        filledQty: Int,
    ) {
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable() ?: return
        runCatching {
            auditRepo.save(
                OrderAuditEvent(
                    orderId = orderId,
                    userId = userId,
                    eventType = eventType,
                    payloadJson = """{"source":"DAY_ORDER_EXPIRY","resolution":"$resolution","filledQty":$filledQty}""",
                    createdAt = Instant.now(clock),
                ),
            )
        }.onFailure { e -> log.error("ExpiredDayOrderSyncWorker writeAudit 실패: orderId={} err={}", orderId, e.message) }
    }

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
        }.onFailure { e -> log.error("SystemEventLogger.emit 실패: action={} err={}", action, e.message) }
    }
}
