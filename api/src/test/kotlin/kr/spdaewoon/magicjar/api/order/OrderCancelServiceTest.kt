package kr.spdaewoon.magicjar.api.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.infrastructure.kis.ApiKisOrderCancelAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderAuditEventRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestRepositoryAdapter
import kr.spdaewoon.magicjar.api.service.order.OrderCancelService
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * OrderCancelServiceTest — ADR 0068 §15-6 LOCK 3 케이스.
 *
 * (a) status OPEN 시 cancel ACK · (b) status FILLED 시 409 · (c) userId 격리
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class OrderCancelServiceTest {
    private val orderRequestAdapter: OrderRequestRepositoryAdapter =
        Mockito.mock(OrderRequestRepositoryAdapter::class.java)
    private val orderAuditAdapter: OrderAuditEventRepositoryAdapter =
        Mockito.mock(OrderAuditEventRepositoryAdapter::class.java)
    private val kisOrderCancelAdapter: ApiKisOrderCancelAdapter =
        Mockito.mock(ApiKisOrderCancelAdapter::class.java)

    private val sut = OrderCancelService(orderRequestAdapter, orderAuditAdapter, kisOrderCancelAdapter, ObjectMapper())

    private fun makeRequest(
        id: Long,
        userId: String,
        status: OrderStatus,
    ) = OrderRequest(
        id = id,
        userId = userId,
        signalId = 1L,
        strategyId = 0L,
        code = "005930",
        side = OrderSide.BUY,
        priceType = OrderPriceType.MARKET,
        quantity = 10,
        limitPrice = null,
        status = status,
        mode = TradingMode.MOCK,
        idempotencyKey = "key-$id",
        kisOrderNo = "ODNO$id",
        kisFwdgOrgno = "ORG$id",
        requestedAt = Instant.now(),
    )

    @Test
    fun `(a) status ACK 시 cancel 성공`() {
        val userId = "user1"
        val req = makeRequest(1L, userId, OrderStatus.ACK)
        val cancelled = req.copy(status = OrderStatus.CANCELLED, completedAt = Instant.now())

        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)
        Mockito.doReturn(OrderResult.Right(Unit)).`when`(kisOrderCancelAdapter).cancel(req)
        Mockito
            .doReturn(
                OrderAuditEvent(
                    orderId = 1L,
                    userId = userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_ACK,
                    payloadJson = "{}",
                    createdAt = Instant.now(),
                ),
            ).`when`(orderAuditAdapter)
            .save(any())
        Mockito.doReturn(cancelled).`when`(orderRequestAdapter).markCompleted(any(), any(), any())

        val result = sut.cancel(1L, userId, null)

        assertEquals(OrderStatus.CANCELLED, result.status)
        verify(kisOrderCancelAdapter).cancel(req)
    }

    @Test
    fun `(b) status FILLED 시 409 Conflict`() {
        val userId = "user1"
        val req = makeRequest(1L, userId, OrderStatus.FILLED)

        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)

        val ex = assertThrows<ResponseStatusException> { sut.cancel(1L, userId, null) }
        assertEquals(409, ex.statusCode.value())
    }

    @Test
    fun `(c) userId 격리 — 다른 사용자 orderId 취소 시 404`() {
        val ownerUserId = "user2"
        val requestingUserId = "user1"
        val req = makeRequest(1L, ownerUserId, OrderStatus.ACK)

        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)

        val ex = assertThrows<ResponseStatusException> { sut.cancel(1L, requestingUserId, null) }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `(d) KIS 취소 실패 시 DB CANCELLED 전이 금지`() {
        val userId = "user1"
        val req = makeRequest(1L, userId, OrderStatus.ACK)
        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)
        Mockito
            .doReturn(OrderResult.Left(DenyReason.KisRejected("1", "reject")))
            .`when`(kisOrderCancelAdapter)
            .cancel(req)
        Mockito
            .doReturn(
                OrderAuditEvent(
                    orderId = 1L,
                    userId = userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_REJECT,
                    payloadJson = "{}",
                    createdAt = Instant.now(),
                ),
            ).`when`(orderAuditAdapter)
            .save(any())

        val ex = assertThrows<ResponseStatusException> { sut.cancel(1L, userId, "test") }

        assertEquals(502, ex.statusCode.value())
        Mockito.verify(orderRequestAdapter, Mockito.never()).markCompleted(any(), any(), any())
    }

    @Test
    fun `(e) KIS ACK 정보 없는 주문은 취소 성공 처리 금지`() {
        val userId = "user1"
        val req = makeRequest(1L, userId, OrderStatus.ACK).copy(kisOrderNo = null, kisFwdgOrgno = null)
        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)

        val ex = assertThrows<ResponseStatusException> { sut.cancel(1L, userId, null) }

        assertEquals(409, ex.statusCode.value())
        Mockito.verify(kisOrderCancelAdapter, Mockito.never()).cancel(any())
        Mockito.verify(orderRequestAdapter, Mockito.never()).markCompleted(any(), any(), any())
    }
}
