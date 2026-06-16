package kr.spdaewoon.magicjar.api.order

import kr.spdaewoon.magicjar.api.persistence.order.OrderAuditEventRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestRepositoryAdapter
import kr.spdaewoon.magicjar.api.service.order.OrderQueryService
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.springframework.data.domain.PageImpl
import java.time.Instant

/**
 * OrderQueryServiceTest — ADR 0068 §15-6 LOCK 3 케이스.
 *
 * (a) 목록 페이징 정상 · (b) 필터 status · (c) userId 격리 (ADR 0010)
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class OrderQueryServiceTest {
    // mock with Mockito.mock to bypass Spring proxy issues
    private val orderRequestAdapter: OrderRequestRepositoryAdapter =
        Mockito.mock(OrderRequestRepositoryAdapter::class.java)
    private val orderExecutionAdapter: OrderExecutionRepositoryAdapter =
        Mockito.mock(OrderExecutionRepositoryAdapter::class.java)
    private val orderAuditAdapter: OrderAuditEventRepositoryAdapter =
        Mockito.mock(OrderAuditEventRepositoryAdapter::class.java)

    private val sut =
        OrderQueryService(
            orderRequestAdapter = orderRequestAdapter,
            orderExecutionAdapter = orderExecutionAdapter,
            orderAuditAdapter = orderAuditAdapter,
        )

    private fun makeRequest(
        id: Long,
        userId: String,
        status: OrderStatus = OrderStatus.FILLED,
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
        requestedAt = Instant.now(),
    )

    @Test
    fun `(a) 목록 페이징 정상 반환`() {
        val userId = "user1"
        val requests = listOf(makeRequest(1L, userId), makeRequest(2L, userId))
        val page = PageImpl(requests)

        Mockito.doReturn(page).`when`(orderRequestAdapter).findByUserAndRange(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
        )

        val result = sut.listByUser(userId, null, null, null, null, null, null, 0, 20)

        assertEquals(2, result.content.size)
        assertEquals(userId, result.content[0].userId)
    }

    @Test
    fun `(b) status 필터 — REQUESTED만 반환`() {
        val userId = "user1"
        val requests = listOf(makeRequest(1L, userId, OrderStatus.REQUESTED))
        val page = PageImpl(requests)

        Mockito.doReturn(page).`when`(orderRequestAdapter).findByUserAndRange(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
        )

        val result = sut.listByUser(userId, null, null, listOf(OrderStatus.REQUESTED), null, null, null, 0, 20)

        assertEquals(1, result.content.size)
        assertEquals(OrderStatus.REQUESTED, result.content[0].status)
    }

    @Test
    fun `(c) userId 격리 — 다른 사용자 orderId 조회 시 null (ADR 0010)`() {
        val requestingUserId = "user1"
        val ownerUserId = "user2"
        val req = makeRequest(1L, ownerUserId)

        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)

        // user1이 user2의 주문 조회 → null
        val result = sut.findDetail(1L, requestingUserId)

        assertNull(result)
    }

    @Test
    fun `단건 조회 정상`() {
        val userId = "user1"
        val req = makeRequest(1L, userId)

        Mockito.doReturn(req).`when`(orderRequestAdapter).findById(1L)
        Mockito.doReturn(emptyList<Any>()).`when`(orderExecutionAdapter).findByOrderId(1L)
        Mockito.doReturn(emptyList<Any>()).`when`(orderAuditAdapter).findByOrderId(1L)

        val result = sut.findDetail(1L, userId)

        assertNotNull(result)
        assertEquals(1L, result!!.request.id)
    }
}
