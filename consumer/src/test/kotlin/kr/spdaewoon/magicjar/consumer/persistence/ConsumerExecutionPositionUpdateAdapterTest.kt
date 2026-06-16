package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ConsumerExecutionPositionUpdateAdapterTest {
    private val repository = mockk<PositionJpaRepository>(relaxed = true)
    private val adapter = ConsumerExecutionPositionUpdateAdapter(repository)

    @Test
    fun `BUY 체결은 기존 수량과 체결금액으로 평단을 재계산한다`() {
        every { repository.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK") } returns
            PositionJpaEntity(userId = "user-A", code = "005930", quantity = 10, avgPrice = BigDecimal("10000"))

        adapter.applyExecution(
            request = request(side = OrderSide.BUY),
            execution = execution(qty = 5, price = "13000"),
        )

        verify(exactly = 1) {
            repository.upsertReconciled(
                userId = "user-A",
                code = "005930",
                quantity = 15,
                avgPrice = BigDecimal("11000.0000"),
                source = "MOCK",
            )
        }
    }

    @Test
    fun `SELL 체결은 보유 수량을 차감하고 전량 매도 시 평단을 0으로 둔다`() {
        every { repository.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK") } returns
            PositionJpaEntity(userId = "user-A", code = "005930", quantity = 5, avgPrice = BigDecimal("10000"))

        adapter.applyExecution(
            request = request(side = OrderSide.SELL),
            execution = execution(qty = 5, price = "12000"),
        )

        verify(exactly = 1) {
            repository.upsertReconciled(
                userId = "user-A",
                code = "005930",
                quantity = 0,
                avgPrice = BigDecimal.ZERO,
                source = "MOCK",
            )
        }
    }

    private fun request(side: OrderSide) =
        OrderRequest(
            id = 1L,
            userId = "user-A",
            signalId = 77L,
            strategyId = 100L,
            code = "005930",
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = 10,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            idempotencyKey = "77:user-A",
            requestedAt = Instant.parse("2026-05-22T00:00:00Z"),
        )

    private fun execution(
        qty: Int,
        price: String,
    ) = OrderExecution(
        id = 10L,
        orderId = 1L,
        userId = "user-A",
        kisOrderNo = "ODNO",
        executedQuantity = qty,
        executedPrice = BigDecimal(price),
        executedAt = Instant.parse("2026-05-22T00:00:01Z"),
        eventId = "ODNO:090001:$qty",
        createdAt = Instant.parse("2026-05-22T00:00:02Z"),
    )
}
