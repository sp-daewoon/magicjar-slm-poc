package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * ExpiredDayOrderSyncWorker 단위 테스트 — canary D2 hotfix #1 (오늘 09:01~09:04 incident 회귀).
 *
 * 전일 발주(어제 09:00) 익절 SELL LIMIT 이 장 마감으로 만료됐는데 DB status=ACK 잔존 → 오늘 매도 시그널이
 * "이미 정합 auto-tp open" 으로 오판해 매도 영구 skip 되던 결함을, 장 마감 후/개장 전 worker 가 KIS 일별체결
 * 대조 후 CANCELLED 동기화해 닫는다.
 *
 * 매트릭스:
 *  (a) Found 미체결(filled=0) → 잔존 ACK 만료 → CANCELLED + 사유 + audit
 *  (b) NotFound → 만료 CANCELLED
 *  (c) Found 전량 체결 → FILLED 동기화(잔존 ACK 정정)
 *  (d) Failed → 상태 불변(다음 tick 재시도, 잘못된 CANCELLED 회피)
 *  (e) kisOrderNo 부재 → CANCELLED(잔존 ACK 제거)
 *  (f) 전일 미종결 0건 → skip
 *  (g) ENV off → no-op
 *  (h) 당일 장마감+grace 이후 미체결 ACK → CANCELLED(배포 drain 가드 해소)
 *
 * AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4).
 */
class ExpiredDayOrderSyncWorkerTest {
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val auditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val kisPort = mockk<KisOrderInquireDailyCcldPort>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    // 2026-06-12 01:00Z = 2026-06-12 10:00 KST (개장 직후). 당일 0시 KST = 2026-06-11T15:00:00Z.
    private val now: Instant = Instant.parse("2026-06-12T01:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"))

    private val orderProvider =
        mockk<ObjectProvider<OrderRequestRepository>>(relaxed = true).also { every { it.getIfAvailable() } returns orderRepo }
    private val auditProvider =
        mockk<ObjectProvider<OrderAuditEventRepository>>(relaxed = true).also { every { it.getIfAvailable() } returns auditRepo }

    private fun worker(
        enabled: Boolean = true,
        clock: Clock = this.clock,
    ) = ExpiredDayOrderSyncWorker(
        orderRequestRepositoryProvider = orderProvider,
        orderAuditEventRepositoryProvider = auditProvider,
        kisOrderInquireDailyCcldPort = kisPort,
        systemEventLogger = systemEventLogger,
        enabled = enabled,
        clock = clock,
    )

    @Test
    fun `incident — 전일 미체결 익절 SELL ACK 잔존 → KIS 미체결 확인 → CANCELLED 동기화`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(priorDayOrder(id = 144L, status = OrderStatus.ACK))
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.REQUESTED, any(), any()) } returns emptyList()
        every { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) } returns
            OrderInquireResult.Found(odno = "ODNO144", krxFwdgOrdOrgno = "00950", filledQty = 0, orderQty = 7, ccldStatus = "01")

        worker().expireTick()

        // 잔존 ACK 만료 → CANCELLED + 사유 + completedAt 종결.
        verify(exactly = 1) { orderRepo.updateStatus(144L, OrderStatus.CANCELLED, match { it.contains("DAY_ORDER_EXPIRED") }) }
        verify(exactly = 1) { orderRepo.markCompleted(144L, any(), OrderStatus.CANCELLED) }
        verify(exactly = 1) { auditRepo.save(match { it.eventType == OrderAuditEventType.KIS_RECONCILE_RESOLVED }) }
    }

    @Test
    fun `전일 ACK — KIS NotFound(미접수) → CANCELLED 만료`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(priorDayOrder(id = 200L, status = OrderStatus.ACK))
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.REQUESTED, any(), any()) } returns emptyList()
        every { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) } returns OrderInquireResult.NotFound

        worker().expireTick()

        verify(exactly = 1) { orderRepo.updateStatus(200L, OrderStatus.CANCELLED, any()) }
        verify(exactly = 1) { orderRepo.markCompleted(200L, any(), OrderStatus.CANCELLED) }
    }

    @Test
    fun `전일 ACK — KIS 전량 체결 확인 → FILLED 동기화(CANCELLED 아님)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(priorDayOrder(id = 301L, status = OrderStatus.ACK))
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.REQUESTED, any(), any()) } returns emptyList()
        every { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) } returns
            OrderInquireResult.Found(odno = "ODNO301", krxFwdgOrdOrgno = "00950", filledQty = 7, orderQty = 7, ccldStatus = "02")

        worker().expireTick()

        verify(exactly = 1) { orderRepo.markCompleted(301L, any(), OrderStatus.FILLED) }
        verify(exactly = 0) { orderRepo.updateStatus(301L, OrderStatus.CANCELLED, any()) }
    }

    @Test
    fun `전일 ACK — KIS 조회 실패(Failed) → 상태 불변(다음 tick 재시도)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(priorDayOrder(id = 400L, status = OrderStatus.ACK))
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.REQUESTED, any(), any()) } returns emptyList()
        every { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) } returns OrderInquireResult.Failed("TIMEOUT")

        worker().expireTick()

        // 잘못된 CANCELLED 회피 — status 변경 0.
        verify(exactly = 0) { orderRepo.updateStatus(any(), any(), any()) }
        verify(exactly = 0) { orderRepo.markCompleted(any(), any(), any()) }
    }

    @Test
    fun `전일 미종결 — kisOrderNo 부재 → CANCELLED(잔존 제거)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns emptyList()
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.REQUESTED, any(), any()) } returns
            listOf(priorDayOrder(id = 500L, status = OrderStatus.REQUESTED, kisOrderNo = null))

        worker().expireTick()

        verify(exactly = 0) { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { orderRepo.updateStatus(500L, OrderStatus.CANCELLED, any()) }
    }

    @Test
    fun `전일 미종결 0건 — skip(KIS 호출 0)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) } returns emptyList()

        worker().expireTick()

        verify(exactly = 0) { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ENV off — no-op(lookup·KIS 0)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) } returns
            listOf(priorDayOrder(id = 144L, status = OrderStatus.ACK))

        worker(enabled = false).expireTick()

        verify(exactly = 0) { orderRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) }
        verify(exactly = 0) { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `당일 장마감 이후 ACK — 미체결이면 CANCELLED 동기화해 drain 잔류를 닫는다`() {
        val afterCloseClock =
            Clock.fixed(
                Instant.parse("2026-06-12T14:00:00Z"), // 2026-06-12 23:00 KST
                ZoneId.of("Asia/Seoul"),
            )
        every {
            orderRepo.findByStatusAndRequestedAtBefore(
                OrderStatus.ACK,
                Instant.parse("2026-06-12T06:50:00Z"), // 15:30 + 20m KST
                any(),
            )
        } returns listOf(sameDayOrder(id = 334L, status = OrderStatus.ACK))
        every {
            orderRepo.findByStatusAndRequestedAtBefore(
                OrderStatus.REQUESTED,
                Instant.parse("2026-06-12T06:50:00Z"),
                any(),
            )
        } returns emptyList()
        every { kisPort.inquireByOrderNo(any(), any(), any(), any(), any()) } returns
            OrderInquireResult.Found(odno = "0030245800", krxFwdgOrdOrgno = "91253", filledQty = 0, orderQty = 1, ccldStatus = "미체결")

        worker(clock = afterCloseClock).expireTick()

        verify(exactly = 1) { orderRepo.updateStatus(334L, OrderStatus.CANCELLED, match { it.contains("DAY_ORDER_EXPIRED") }) }
        verify(exactly = 1) { orderRepo.markCompleted(334L, any(), OrderStatus.CANCELLED) }
        verify(exactly = 1) { auditRepo.save(match { it.eventType == OrderAuditEventType.KIS_RECONCILE_RESOLVED }) }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun priorDayOrder(
        id: Long,
        status: OrderStatus,
        kisOrderNo: String? = "ODNO$id",
    ): OrderRequest =
        OrderRequest(
            id = id,
            userId = "user-A",
            signalId = 0L,
            strategyId = 8L,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = 7,
            limitPrice = java.math.BigDecimal("73500"),
            status = status,
            mode = TradingMode.MOCK,
            kisOrderNo = kisOrderNo,
            kisFwdgOrgno = "00950",
            idempotencyKey = "auto-tp:$id",
            // 전일(어제 09:00 KST = 2026-06-11T00:00:00Z) 발주 — 당일 0시 KST 이전.
            requestedAt = Instant.parse("2026-06-11T00:00:00Z"),
            acknowledgedAt = Instant.parse("2026-06-11T00:00:05Z"),
        )

    private fun sameDayOrder(
        id: Long,
        status: OrderStatus,
        kisOrderNo: String? = "0030245800",
    ): OrderRequest =
        OrderRequest(
            id = id,
            userId = "74683223",
            signalId = 0L,
            strategyId = 88L,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = 1,
            limitPrice = java.math.BigDecimal("345000"),
            status = status,
            mode = TradingMode.REAL,
            kisOrderNo = kisOrderNo,
            kisFwdgOrgno = "91253",
            idempotencyKey = "auto-tp:$id",
            // 당일 15:10:02 KST — 정규장 마감+grace 이후에는 만료 대조 대상.
            requestedAt = Instant.parse("2026-06-12T06:10:02Z"),
            acknowledgedAt = Instant.parse("2026-06-12T06:10:02Z"),
        )
}
