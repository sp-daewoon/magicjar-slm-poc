package kr.spdaewoon.magicjar.consumer.reconciliation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

class SymbolDetailMinuteGapFillServiceTest {
    private val candleRepository = mockk<CandleRepository>()
    private val rangePort = mockk<KisOneMinuteCandleRangePort>()
    private val aggregateBackfillRunner = mockk<AggregateBackfillRunner>()
    private val stateStore = mockk<CandleReconciliationStateStore>(relaxed = true)

    private fun service(): SymbolDetailMinuteGapFillService =
        SymbolDetailMinuteGapFillService(
            candleRepository = candleRepository,
            kisOneMinuteCandleRangePort = rangePort,
            aggregateBackfillRunner = aggregateBackfillRunner,
            reconciliationStateStore = stateStore,
        )

    @Test
    fun `range fill은 지정 범위만 KIS에서 가져와 저장하고 삽입 시 합산봉을 재생성한다`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:03:00Z")
        val fetched = listOf(candle("005930", "2026-06-01T00:01:00Z"))
        val savedSlot = slot<List<Candle>>()

        every { rangePort.fetchOneMinuteCandlesBetween("005930", from, to) } returns fetched
        every { candleRepository.saveAll(capture(savedSlot)) } returns 1
        every { aggregateBackfillRunner.backfill("005930") } returns mapOf("3m" to 1)

        service().runRangeFill("005930", from, to)

        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.first().source).isEqualTo("KIS-RANGE-GAPFILL")
        verify(exactly = 1) { aggregateBackfillRunner.backfill("005930") }
        verify(exactly = 1) { stateStore.markReady("005930", CandleInterval.ONE_MIN, "range-gapfill-completed") }
    }

    @Test
    fun `range fill 실패 시 readiness state를 FAILED로 닫는다`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:03:00Z")

        every { rangePort.fetchOneMinuteCandlesBetween("005930", from, to) } throws RuntimeException("KIS down")

        service().runRangeFill("005930", from, to)

        verify(exactly = 1) { stateStore.markFailed("005930", CandleInterval.ONE_MIN, "range-gapfill-failed") }
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        verify(exactly = 0) { aggregateBackfillRunner.backfill(any()) }
    }

    @Test
    fun `range fill 후 합산봉 재생성 실패 시 readiness state를 FAILED로 닫는다`() {
        val from = Instant.parse("2026-06-01T00:00:00Z")
        val to = Instant.parse("2026-06-01T00:03:00Z")
        val fetched = listOf(candle("005930", "2026-06-01T00:01:00Z"))

        every { rangePort.fetchOneMinuteCandlesBetween("005930", from, to) } returns fetched
        every { candleRepository.saveAll(any()) } returns 1
        every { aggregateBackfillRunner.backfill("005930") } returns mapOf("3m" to -1)

        service().runRangeFill("005930", from, to)

        verify(exactly = 1) { stateStore.markFailed("005930", CandleInterval.ONE_MIN, "range-gapfill-failed") }
        verify(exactly = 0) { stateStore.markReady("005930", CandleInterval.ONE_MIN, any()) }
    }

    @Test
    fun `submitIfNeeded는 last DB 1m 다음 분부터 현재 직전분까지만 비동기 큐잉한다`() {
        val lastTs = Instant.now().minusSeconds(180)
        every { candleRepository.findMaxTsByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns lastTs
        every { rangePort.fetchOneMinuteCandlesBetween("005930", any(), any()) } returns emptyList()
        every { candleRepository.saveAll(emptyList()) } returns 0

        val service = service()
        service.submitIfNeeded("005930")

        verify(timeout = 1000L) {
            rangePort.fetchOneMinuteCandlesBetween(
                "005930",
                lastTs.plusSeconds(60),
                any(),
            )
        }
        verify(exactly = 1) { stateStore.markReconciling("005930", CandleInterval.ONE_MIN, "symbol-detail-enter") }
        service.shutdown()
    }

    private fun candle(
        code: String,
        ts: String,
    ): Candle {
        val price = BigDecimal("100")
        return Candle(
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = KtInstant.parse(ts),
            openRaw = price,
            highRaw = price,
            lowRaw = price,
            closeRaw = price,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "KIS",
        )
    }
}
