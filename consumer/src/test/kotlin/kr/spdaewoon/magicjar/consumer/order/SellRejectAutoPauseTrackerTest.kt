package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/**
 * ADR 0115 §P2-2 — SELL 거부 N회 누적 시 전략 자동 정지 + 1회 알림 테스트.
 *
 * incident(유령 수량 매도 거부 폭주) 회귀 가드: 임계 도달까지 autoDisable 0, 도달 시 1회만, 성공 체결 시 카운터 reset.
 */
class SellRejectAutoPauseTrackerTest {
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>(relaxed = true)
    private val rateLimiter = mockk<NotificationRateLimiterPort>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    private fun tracker(threshold: Int = 3) =
        SellRejectAutoPauseTracker(
            strategyRepositoryProvider = providerOf(strategyRepo),
            rateLimiterProvider = providerOf(rateLimiter),
            systemEventLoggerProvider = providerOf(systemEventLogger),
            rejectAutoPauseThreshold = threshold,
        )

    @Test
    fun `임계 미달 — autoDisable 0`() {
        every { rateLimiter.shouldEmitOncePerWindow(any(), any(), any()) } returns true
        val t = tracker(threshold = 3)
        repeat(2) { t.onOrderRejected(sellOrder()) }
        verify(exactly = 0) { strategyRepo.autoDisable(any(), any()) }
    }

    @Test
    fun `임계 도달 — autoDisable 1회 + 알림 1회`() {
        every { rateLimiter.shouldEmitOncePerWindow(any(), any(), any()) } returns true
        every { strategyRepo.autoDisable(any(), any()) } returns 1
        val t = tracker(threshold = 3)

        repeat(3) { t.onOrderRejected(sellOrder()) }

        verify(exactly = 1) { strategyRepo.autoDisable(100L, any()) }
        verify(exactly = 1) { systemEventLogger.emit(any()) }
    }

    @Test
    fun `BUY 거부는 집계 0`() {
        val t = tracker(threshold = 1)
        t.onOrderRejected(sellOrder(side = OrderSide.BUY))
        verify(exactly = 0) { strategyRepo.autoDisable(any(), any()) }
    }

    @Test
    fun `MANUAL sentinel(strategyId 0) 집계 0`() {
        val t = tracker(threshold = 1)
        t.onOrderRejected(sellOrder(strategyId = 0L))
        verify(exactly = 0) { strategyRepo.autoDisable(any(), any()) }
    }

    @Test
    fun `성공 체결 reset — 누적 끊김(다시 임계까지 발동 안 함)`() {
        every { rateLimiter.shouldEmitOncePerWindow(any(), any(), any()) } returns true
        val t = tracker(threshold = 3)

        repeat(2) { t.onOrderRejected(sellOrder()) }
        t.resetOnSuccess(sellOrder()) // reset
        repeat(2) { t.onOrderRejected(sellOrder()) } // 다시 2회 — 임계 3 미달

        verify(exactly = 0) { strategyRepo.autoDisable(any(), any()) }
    }

    @Test
    fun `rate limiter 디바운스 — 윈도 차단이면 알림 emit 0`() {
        every { rateLimiter.shouldEmitOncePerWindow(any(), any(), any()) } returns false
        every { strategyRepo.autoDisable(any(), any()) } returns 1
        val t = tracker(threshold = 1)

        t.onOrderRejected(sellOrder())

        // 정지(autoDisable)는 수행하되 알림은 디바운스로 skip.
        verify(exactly = 1) { strategyRepo.autoDisable(any(), any()) }
        verify(exactly = 0) { systemEventLogger.emit(any()) }
    }

    private fun sellOrder(
        side: OrderSide = OrderSide.SELL,
        strategyId: Long = 100L,
    ): OrderRequest =
        OrderRequest(
            id = 9L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = 5,
            limitPrice = null,
            status = OrderStatus.REJECTED,
            mode = TradingMode.MOCK,
            idempotencyKey = "k-$side-$strategyId",
            requestedAt = Instant.EPOCH,
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
            every { it.ifAvailable } returns value
        }
}
