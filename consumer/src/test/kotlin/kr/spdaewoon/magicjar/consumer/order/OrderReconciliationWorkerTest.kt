package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderInquireDailyCcldPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderInquireResult
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * OrderReconciliationWorker 단위 테스트 (ADR 0084 §2-2 P0-2 — PENDING_RECONCILE 분기 매트릭스 verify).
 *
 * ### 4+ 케이스 매트릭스
 *  (a) Found + filledQty == orderQty → PENDING_RECONCILE → FILLED + audit KIS_RECONCILE_RESOLVED
 *  (b) Found + filledQty == 0 → PENDING_RECONCILE → ACK + audit KIS_RECONCILE_RESOLVED
 *  (c) NotFound → PENDING_RECONCILE → REJECTED + audit KIS_RECONCILE_RESOLVED
 *  (d) Failed → 그대로 PENDING_RECONCILE + audit 미발행 (다음 tick 재시도)
 *  (e) elapsed > 15분 → PENDING_RECONCILE → EXPIRED + audit KIS_RECONCILE_TIMEOUT
 *  (f) kisOrderNo 부재 → skip (운영자 검증 위임 — 다음 tick에서 EXPIRED 진입)
 *  (g) PENDING_RECONCILE 0건 → cron skip (early return)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-2).
 */
class OrderReconciliationWorkerTest {
    private val orderRequestRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val orderAuditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val kisInquirePort = mockk<KisOrderInquireDailyCcldPort>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val now: Instant = Instant.parse("2026-05-18T10:00:00Z")
    private val fixedClock: Clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"))

    private val orderRequestProvider =
        mockk<ObjectProvider<OrderRequestRepository>>(relaxed = true).also {
            every { it.getIfAvailable() } returns orderRequestRepo
        }
    private val orderAuditProvider =
        mockk<ObjectProvider<OrderAuditEventRepository>>(relaxed = true).also {
            every { it.getIfAvailable() } returns orderAuditRepo
        }

    private val worker =
        OrderReconciliationWorker(
            orderRequestRepositoryProvider = orderRequestProvider,
            orderAuditEventRepositoryProvider = orderAuditProvider,
            kisOrderInquireDailyCcldPort = kisInquirePort,
            objectMapper = objectMapper,
            systemEventLogger = systemEventLogger,
            clock = fixedClock,
        )

    @Test
    fun `(a) Found filledQty == orderQty → FILLED + audit RESOLVED`() {
        val row = stubPendingRow(id = 100L, kisOrderNo = "0000123456", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns
            OrderInquireResult.Found(
                odno = "0000123456",
                krxFwdgOrdOrgno = "ORG001",
                filledQty = 10,
                orderQty = 10,
                ccldStatus = "체결완료",
            )

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.updateKisAck(100L, "0000123456", "ORG001", now) }
        verify(exactly = 1) { orderRequestRepo.markCompleted(100L, now, OrderStatus.FILLED) }

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { orderAuditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_RECONCILE_RESOLVED)
        assertThat(auditSlot.captured.orderId).isEqualTo(100L)
        assertThat(auditSlot.captured.payloadJson).contains("\"finalStatus\":\"FILLED\"")
    }

    @Test
    fun `(b) Found filledQty == 0 → ACK only (markCompleted 미호출)`() {
        val row = stubPendingRow(id = 101L, kisOrderNo = "0000999888", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns
            OrderInquireResult.Found(
                odno = "0000999888",
                krxFwdgOrdOrgno = "ORG002",
                filledQty = 0,
                orderQty = 10,
                ccldStatus = "미체결",
            )

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.updateKisAck(101L, "0000999888", "ORG002", now) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 1) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(c) NotFound → REJECTED + audit RESOLVED`() {
        val row = stubPendingRow(id = 102L, kisOrderNo = "0000555444", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.NotFound

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.markCompleted(102L, now, OrderStatus.REJECTED) }
        verify(exactly = 0) { orderRequestRepo.updateKisAck(any(), any(), any(), any()) }

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { orderAuditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_RECONCILE_RESOLVED)
        assertThat(auditSlot.captured.payloadJson).contains("\"resolution\":\"NOT_FOUND\"")
        assertThat(auditSlot.captured.payloadJson).contains("\"finalStatus\":\"REJECTED\"")
    }

    @Test
    fun `(d) Failed → status 유지 + audit 미발행 (다음 tick 재시도)`() {
        val row = stubPendingRow(id = 103L, kisOrderNo = "0000777666", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.Failed("HTTP_503")

        worker.reconcileTick()

        verify(exactly = 0) { orderRequestRepo.updateKisAck(any(), any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(e) elapsed greater than 15분 → EXPIRED + audit TIMEOUT`() {
        // requestedAt = now - 16분 → elapsed > EXPIRY_THRESHOLD (15분)
        val row = stubPendingRow(id = 104L, kisOrderNo = "0000333222", elapsedMinutes = 16)
        stubPendingLookup(listOf(row))

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.markCompleted(104L, now, OrderStatus.EXPIRED) }
        // KIS inquire-daily-ccld는 호출되지 않음 — EXPIRED 분기는 timeout 직진
        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { orderAuditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_RECONCILE_TIMEOUT)
    }

    @Test
    fun `(f) kisOrderNo 부재 → skip (KIS 호출 0건, status 유지)`() {
        val row = stubPendingRow(id = 105L, kisOrderNo = null, elapsedMinutes = 7)
        stubPendingLookup(listOf(row))

        worker.reconcileTick()

        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.updateKisAck(any(), any(), any(), any()) }
        verify(exactly = 0) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(g) PENDING_RECONCILE 0건 → cron early return (KIS 호출 0건)`() {
        stubPendingLookup(emptyList())

        worker.reconcileTick()

        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(h) Repository 미합류 → graceful skip (Phase 2 미완 안전)`() {
        every { orderRequestProvider.getIfAvailable() } returns null

        worker.reconcileTick()

        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `(i) inquireByOrderNo 호출 시 day range = today minus 1 to today (KST)`() {
        val row = stubPendingRow(id = 200L, kisOrderNo = "0000111000", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.NotFound

        worker.reconcileTick()

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        verify(exactly = 1) {
            kisInquirePort.inquireByOrderNo(
                userId = "user-001",
                mode = TradingMode.MOCK,
                kisOrderNo = "0000111000",
                inquireStartDate = today.minusDays(1),
                inquireEndDate = today,
            )
        }
    }

    // ===== SystemEvent emit (P1 spec §6.1) =====

    /**
     * (j) PENDING_RECONCILE 1건 이상 발견 시 SystemEventLogger emit RECONCILE_FALLBACK_FIRED.
     *
     * spec §6.1 P1 emit point #1:
     *  - category = RECONCILE / severity = INFO / actor = OrderReconciliationWorker
     *  - userId = null (system-wide audit — 다중 사용자 row 합산)
     *  - detailJson = {"pendingCount":N}
     */
    @Test
    fun `(j) FIRED emit — PENDING_RECONCILE 1건 이상 발견 시 SystemEventLogger emit RECONCILE_FALLBACK_FIRED (INFO)`() {
        val row = stubPendingRow(id = 300L, kisOrderNo = "0000444555", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.NotFound

        worker.reconcileTick()

        val eventSlot = slot<SystemEvent>()
        verify(atLeast = 1) { systemEventLogger.emit(capture(eventSlot)) }
        val firedEvent = eventSlot.captured
        assertThat(firedEvent.category).isEqualTo(SystemEventCategory.RECONCILE)
        assertThat(firedEvent.action).isEqualTo("RECONCILE_FALLBACK_FIRED")
        assertThat(firedEvent.severity).isEqualTo(SystemEventSeverity.INFO)
        assertThat(firedEvent.actor).isEqualTo("OrderReconciliationWorker")
        assertThat(firedEvent.userId).isNull()
        assertThat(firedEvent.detailJson).contains("\"pendingCount\":1")
    }

    /**
     * (k) Found + filledQty > 0 시 SystemEventLogger emit RECONCILE_FALLBACK_RESOLVED.
     *
     * spec §6.1 P1 emit point #2:
     *  - category = RECONCILE / severity = WARN / actor = OrderReconciliationWorker
     *  - userId = order.userId (사용자별 audit)
     *  - detailJson = {"orderId":N,"filledQty":N,"orderQty":N}
     */
    @Test
    fun `(k) RESOLVED emit — KIS Found + filledQty greater than 0 시 RECONCILE_FALLBACK_RESOLVED (WARN + userId + filledQty)`() {
        val row = stubPendingRow(id = 301L, kisOrderNo = "0000666777", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns
            OrderInquireResult.Found(
                odno = "0000666777",
                krxFwdgOrdOrgno = "ORG301",
                filledQty = 7,
                orderQty = 10,
                ccldStatus = "부분체결",
            )

        worker.reconcileTick()

        // FIRED + RESOLVED 둘 다 emit — 2건 capture
        val events = mutableListOf<SystemEvent>()
        verify(atLeast = 2) { systemEventLogger.emit(capture(events)) }

        val resolvedEvent = events.first { it.action == "RECONCILE_FALLBACK_RESOLVED" }
        assertThat(resolvedEvent.category).isEqualTo(SystemEventCategory.RECONCILE)
        assertThat(resolvedEvent.severity).isEqualTo(SystemEventSeverity.WARN)
        assertThat(resolvedEvent.actor).isEqualTo("OrderReconciliationWorker")
        assertThat(resolvedEvent.userId).isEqualTo("user-001")
        assertThat(resolvedEvent.detailJson).contains("\"orderId\":301")
        assertThat(resolvedEvent.detailJson).contains("\"filledQty\":7")
        assertThat(resolvedEvent.detailJson).contains("\"orderQty\":10")
    }

    /**
     * (l) Found + filledQty == 0 시 RESOLVED emit 0 — ACK 분기는 자본 audit 의무 없음 (FIRED만 1회).
     *
     * 진보 페르소나 lens: RESOLVED는 "실제 체결 발견" 즉 자본 영향 발생 시점 audit.
     * filledQty == 0 (ACK only)는 자본 영향 0 → audit noise 회피.
     */
    @Test
    fun `(l) Found filledQty == 0 시 RESOLVED emit 0 (FIRED 1회만)`() {
        val row = stubPendingRow(id = 302L, kisOrderNo = "0000888999", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns
            OrderInquireResult.Found(
                odno = "0000888999",
                krxFwdgOrdOrgno = "ORG302",
                filledQty = 0,
                orderQty = 10,
                ccldStatus = "미체결",
            )

        worker.reconcileTick()

        val events = mutableListOf<SystemEvent>()
        verify { systemEventLogger.emit(capture(events)) }
        // FIRED 1건만 — RESOLVED 0건
        assertThat(events).hasSize(1)
        assertThat(events[0].action).isEqualTo("RECONCILE_FALLBACK_FIRED")
    }

    private fun stubPendingLookup(rows: List<OrderRequest>) {
        every {
            orderRequestRepo.findByStatusAndRequestedAtBefore(
                status = OrderStatus.PENDING_RECONCILE,
                cutoff = any(),
                limit = any(),
            )
        } returns rows
    }

    private fun stubPendingRow(
        id: Long,
        kisOrderNo: String?,
        elapsedMinutes: Long,
    ) = OrderRequest(
        id = id,
        userId = "user-001",
        signalId = 42L,
        strategyId = 100L,
        code = "005930",
        side = OrderSide.BUY,
        priceType = OrderPriceType.MARKET,
        quantity = 10,
        limitPrice = null,
        status = OrderStatus.PENDING_RECONCILE,
        mode = TradingMode.MOCK,
        kisOrderNo = kisOrderNo,
        kisFwdgOrgno = null,
        idempotencyKey = "42:user-001",
        rejectReason = "KIS_RECONCILE_PENDING:KIS_5XX:503",
        requestedAt = now.minusSeconds(elapsedMinutes * 60),
        acknowledgedAt = null,
        completedAt = null,
    )
}
