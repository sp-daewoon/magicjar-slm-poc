package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HoldingDemandRegistrarTest {
    private val router = mockk<RealtimeSubscriptionRouter>(relaxed = true)
    private val index = mockk<HoldingPositionIndex>()

    @Test
    fun `refreshHoldingDemands 는 인덱스를 새로고침하고 사라진 HOLDING demand 를 release 한다`() {
        every { index.refresh() } just runs
        every { index.holdingKeys() } returns listOf(held("005930")) andThen emptyList()
        val registrar = HoldingDemandRegistrar(router, index, enabled = true)

        registrar.refreshHoldingDemands()
        registrar.refreshHoldingDemands()

        verify(exactly = 2) { index.refresh() }
        verify(exactly = 1) {
            router.request(owner = RealtimeDemandOwner.HOLDING, ownerId = "005930", code = "005930")
        }
        verify(exactly = 1) {
            router.release(owner = RealtimeDemandOwner.HOLDING, ownerId = "005930", code = "005930")
        }
    }

    @Test
    fun `onFullyExited 는 기억된 HOLDING demand 를 즉시 release 한다`() {
        val registrar = HoldingDemandRegistrar(router, index, enabled = true)

        registrar.onBuyFilled("005930")
        registrar.onFullyExited("005930")

        verify(exactly = 1) {
            router.request(owner = RealtimeDemandOwner.HOLDING, ownerId = "005930", code = "005930")
        }
        verify(exactly = 1) {
            router.release(owner = RealtimeDemandOwner.HOLDING, ownerId = "005930", code = "005930")
        }
    }

    private fun held(code: String): HoldingPositionIndex.HeldPosition =
        HoldingPositionIndex.HeldPosition(
            userId = "user-A",
            strategyId = 85L,
            code = code,
            quantity = 1,
            avgPrice = BigDecimal("70000"),
            stopLossPct = BigDecimal("1.0"),
            takeProfitPct = BigDecimal("2.0"),
            updatedAtEpoch = 1_781_493_600L,
            viActionPolicy = ViActionPolicy.ALERT_ONLY,
        )
}
