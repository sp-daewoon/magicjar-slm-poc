package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * [EntryTimeoutCancelWorker] 단위 테스트 — ADR 0114 §3-2 (R2-C2a).
 */
class EntryTimeoutCancelWorkerTest {
    private val repo = mockk<OrderRequestRepository>(relaxed = true)
    private val auditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>()
    private val objectMapper = ObjectMapper()
    private val now = Instant.parse("2026-05-22T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun worker(enabled: Boolean = true): EntryTimeoutCancelWorker =
        EntryTimeoutCancelWorker(
            orderRequestRepositoryProvider = providerOf(repo),
            orderAuditEventRepositoryProvider = providerOf(auditRepo),
            kisOrderPort = kisOrderPort,
            objectMapper = objectMapper,
            enabled = enabled,
            defaultTimeoutSec = 15,
            clock = clock,
        )

    private fun openBuyLimit(
        id: Long = 1L,
        ackPresent: Boolean = true,
        requestedAt: Instant = now.minus(Duration.ofSeconds(30)),
        status: OrderStatus = OrderStatus.ACK,
    ): OrderRequest =
        OrderRequest(
            id = id,
            userId = "user-A",
            signalId = 7L,
            strategyId = 100L,
            code = "005930",
            side = OrderSide.BUY,
            priceType = OrderPriceType.LIMIT,
            quantity = 10,
            limitPrice = BigDecimal("50000"),
            status = status,
            mode = TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0001234567" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = "7:user-A",
            requestedAt = requestedAt,
        )

    @Test
    fun `타임아웃 도달 + ACK 확정 — KIS 취소 성공 시 CANCELLED 마킹 + audit`() {
        every { repo.findOpenBuyLimitBefore(any(), any()) } returns listOf(openBuyLimit())
        every { kisOrderPort.cancel(any(), TradingMode.MOCK) } returns OrderResult.Right(Unit)
        val auditSlot = slot<OrderAuditEvent>()
        every { auditRepo.save(capture(auditSlot)) } answers { auditSlot.captured }

        worker().cancelTick()

        verify(exactly = 1) { kisOrderPort.cancel(match { it.id == 1L }, TradingMode.MOCK) }
        verify(exactly = 1) { repo.markCompleted(1L, now, OrderStatus.CANCELLED) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_CANCEL_ACK)
        assertThat(auditSlot.captured.payloadJson).contains("ENTRY_TIMEOUT")
    }

    @Test
    fun `ACK 미확정(kisOrderNo 부재) — 취소 호출 0 + 상태 유지(보류)`() {
        every { repo.findOpenBuyLimitBefore(any(), any()) } returns
            listOf(openBuyLimit(ackPresent = false, status = OrderStatus.REQUESTED))

        worker().cancelTick()

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 0) { repo.markCompleted(any(), any(), any()) }
    }

    @Test
    fun `KIS 취소 실패 — DB status 유지(거짓 CANCELLED 0) + reject audit`() {
        every { repo.findOpenBuyLimitBefore(any(), any()) } returns listOf(openBuyLimit())
        every { kisOrderPort.cancel(any(), any()) } returns
            OrderResult.Left(DenyReason.KisRejected(rtCd = "1", msg = "장 종료"))
        val auditSlot = slot<OrderAuditEvent>()
        every { auditRepo.save(capture(auditSlot)) } answers { auditSlot.captured }

        worker().cancelTick()

        // markCompleted 호출 0 — 거짓 CANCELLED 금지(자본 path 1순위).
        verify(exactly = 0) { repo.markCompleted(any(), any(), any()) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_CANCEL_REJECT)
    }

    @Test
    fun `후보 0건(이미 체결 등으로 query 미반환) — 취소 호출 0`() {
        // FILLED/CANCELLED는 findOpenBuyLimitBefore 자체가 반환 안 함(query status 필터). 빈 리스트 = no-op.
        every { repo.findOpenBuyLimitBefore(any(), any()) } returns emptyList()

        worker().cancelTick()

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
    }

    @Test
    fun `worker 비활성 — lookup·취소 호출 0`() {
        worker(enabled = false).cancelTick()
        verify(exactly = 0) { repo.findOpenBuyLimitBefore(any(), any()) }
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
    }

    @Test
    fun `cutoff — now 에서 전역 타임아웃(15초)만큼 뺀 시각으로 lookup`() {
        val cutoffSlot = slot<Instant>()
        every { repo.findOpenBuyLimitBefore(capture(cutoffSlot), any()) } returns emptyList()

        worker().cancelTick()

        assertThat(cutoffSlot.captured).isEqualTo(now.minus(Duration.ofSeconds(15)))
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}
