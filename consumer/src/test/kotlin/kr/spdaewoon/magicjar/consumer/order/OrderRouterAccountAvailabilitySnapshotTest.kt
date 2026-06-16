package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshot
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshotPort
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderIdempotencyGuard
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.TradingModeGate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

class OrderRouterAccountAvailabilitySnapshotTest {
    @Test
    fun `snapshot guard disabled면 snapshot 포트를 호출하지 않는다`() {
        val port = mockk<AccountAvailabilitySnapshotPort>(relaxed = true)
        val listener =
            listener(
                port = port,
                snapshotGuardEnabled = false,
                rejectOnInsufficient = true,
            )

        val reason = listener.evaluateAccountAvailabilitySnapshot(intent(quantity = 10), TradingMode.REAL)

        assertThat(reason).isNull()
        verify(exactly = 0) { port.read(any(), any()) }
    }

    @Test
    fun `BUY cashD2 snapshot이 부족하고 reject flag ON이면 REJECTED reason을 반환한다`() {
        val port =
            mockk<AccountAvailabilitySnapshotPort>().also {
                every { it.read("12345678", TradingMode.REAL) } returns
                    snapshot(
                        cashD2 = BigDecimal("150000"),
                        sellableQtyByCode = emptyMap(),
                    )
            }
        val listener =
            listener(
                port = port,
                snapshotGuardEnabled = true,
                rejectOnInsufficient = true,
            )

        val reason = listener.evaluateAccountAvailabilitySnapshot(intent(quantity = 10), TradingMode.REAL)

        assertThat(reason).isEqualTo("KIS_ORDER_AVAILABILITY_SNAPSHOT:INSUFFICIENT_CASH:150000<550000")
    }

    @Test
    fun `SELL sellable snapshot이 부족하고 reject flag ON이면 REJECTED reason을 반환한다`() {
        val port =
            mockk<AccountAvailabilitySnapshotPort>().also {
                every { it.read("12345678", TradingMode.REAL) } returns
                    snapshot(
                        cashD2 = BigDecimal("1000000"),
                        sellableQtyByCode = mapOf("005930" to 3),
                    )
            }
        val listener =
            listener(
                port = port,
                snapshotGuardEnabled = true,
                rejectOnInsufficient = true,
            )

        val reason =
            listener.evaluateAccountAvailabilitySnapshot(
                intent(quantity = 10, side = OrderSide.SELL),
                TradingMode.REAL,
            )

        assertThat(reason).isEqualTo("KIS_ORDER_AVAILABILITY_SNAPSHOT:INSUFFICIENT_SELLABLE_QTY:3<10")
    }

    @Test
    fun `부족 snapshot이어도 reject flag OFF면 관측만 하고 통과한다`() {
        val port =
            mockk<AccountAvailabilitySnapshotPort>().also {
                every { it.read("12345678", TradingMode.REAL) } returns
                    snapshot(
                        cashD2 = BigDecimal("150000"),
                        sellableQtyByCode = emptyMap(),
                    )
            }
        val listener =
            listener(
                port = port,
                snapshotGuardEnabled = true,
                rejectOnInsufficient = false,
            )

        val reason = listener.evaluateAccountAvailabilitySnapshot(intent(quantity = 10), TradingMode.REAL)

        assertThat(reason).isNull()
    }

    private fun listener(
        port: AccountAvailabilitySnapshotPort,
        snapshotGuardEnabled: Boolean,
        rejectOnInsufficient: Boolean,
    ) = OrderRouterKafkaListener(
        orderRequestRepositoryProvider = provider(mockk<OrderRequestRepository>(relaxed = true)),
        orderAuditEventRepositoryProvider = provider(mockk<OrderAuditEventRepository>(relaxed = true)),
        killSwitchQueryPortProvider = provider(mockk<KillSwitchQueryPort>(relaxed = true)),
        tradingModeGate = mockk<TradingModeGate>(relaxed = true),
        orderIdempotencyGuard = mockk<OrderIdempotencyGuard>(relaxed = true),
        kisOrderPort = mockk<KisOrderPort>(relaxed = true),
        orderRequestedKafkaProducer = mockk<OrderRequestedKafkaProducer>(relaxed = true),
        orderRejectedKafkaProducer = mockk<OrderRejectedKafkaProducer>(relaxed = true),
        accountBalanceChangedKafkaProducer = mockk<AccountBalanceChangedKafkaProducer>(relaxed = true),
        executionBackfillServiceProvider = provider(null),
        killSwitchJpaRepositoryProvider = provider(mockk<KillSwitchJpaRepository>(relaxed = true)),
        marketSessionResolver = mockk<MarketSessionResolver>(relaxed = true),
        businessDayCalendarProvider = provider(mockk<BusinessDayCalendar>(relaxed = true)),
        objectMapper = ObjectMapper(),
        holdingDemandRegistrarProvider = provider(null),
        sellRejectAutoPauseTracker = mockk<SellRejectAutoPauseTracker>(relaxed = true),
        accountAvailabilitySnapshotPortProvider = provider(port),
        marketHoursGateEnabled = false,
        accountAvailabilitySnapshotGuardEnabled = snapshotGuardEnabled,
        accountAvailabilityRejectOnInsufficient = rejectOnInsufficient,
    )

    private fun intent(
        quantity: Int,
        side: OrderSide = OrderSide.BUY,
    ) = OrderIntent(
        id = 1L,
        userId = "12345678",
        signalId = 42L,
        strategyId = 100L,
        code = "005930",
        side = side,
        priceType = OrderPriceType.LIMIT,
        quantity = quantity,
        limitPrice = BigDecimal("55000"),
        idempotencyKey = "42:12345678",
        requestedAt = Instant.parse("2026-06-12T01:00:00Z"),
    )

    private fun snapshot(
        cashD2: BigDecimal,
        sellableQtyByCode: Map<String, Int>,
    ) = AccountAvailabilitySnapshot(
        userId = "12345678",
        mode = TradingMode.REAL,
        cashBalance = cashD2,
        cashD1 = cashD2,
        cashD2 = cashD2,
        netAssetValue = BigDecimal("10000000"),
        holdingQtyByCode = sellableQtyByCode,
        sellableQtyByCode = sellableQtyByCode,
        versionMillis = 1_000L,
        refreshedAt = Instant.now(),
    )

    private fun <T> provider(value: T?): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }
}
