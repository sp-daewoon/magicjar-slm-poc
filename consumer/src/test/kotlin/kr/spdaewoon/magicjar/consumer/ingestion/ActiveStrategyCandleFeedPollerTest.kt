package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.market.LiveCandleUpdater
import kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ActiveStrategyCandleFeedPollerTest {
    private val activeStrategyCodeProvider = mockk<ActiveStrategyCodeProvider>()
    private val kisMultiPricePort = mockk<KisMultiPricePort>()
    private val liveCandleUpdater = mockk<LiveCandleUpdater>()
    private val stopLossWatcher = mockk<PositionStopLossTickWatcher>(relaxed = true)
    private val kisWebSocketBridge = mockk<KisWebSocketBridge>()
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val clock = Clock.fixed(Instant.parse("2026-06-16T01:00:05Z"), ZoneOffset.UTC) // 10:00:05 KST

    private lateinit var poller: ActiveStrategyCandleFeedPoller

    @BeforeEach
    fun setUp() {
        poller =
            ActiveStrategyCandleFeedPoller(
                activeStrategyCodeProvider = activeStrategyCodeProvider,
                kisMultiPricePort = kisMultiPricePort,
                liveCandleUpdater = liveCandleUpdater,
                positionStopLossTickWatcher = stopLossWatcher,
                kisWebSocketBridge = kisWebSocketBridge,
                redisTemplate = redisTemplate,
                enabled = true,
                staleAfterSeconds = 10L,
                maxCodesPerCycle = 30,
                clock = clock,
            )
        every { liveCandleUpdater.update(any()) } just Runs
    }

    @Test
    fun `진행봉이 없으면 ACTIVE 전략 종목을 REALTIME_MARKET 우선순위로 polling한다`() {
        every { activeStrategyCodeProvider.activeCodes() } returns setOf("093370")
        every { redisTemplate.hasKey("market:candle:current:code:093370:interval:1m") } returns false
        every { kisWebSocketBridge.lastTickAgeSeconds("093370") } returns 2L
        every {
            kisMultiPricePort.fetchMultiPrice(listOf("093370"), "J", KisCallPriority.REALTIME_MARKET)
        } returns listOf(quote("093370", close = "12340", accumulatedVolume = 10_000L))

        val tickSlot = slot<TickEvent>()
        every { liveCandleUpdater.update(capture(tickSlot)) } just Runs

        poller.poll()

        verify(exactly = 1) {
            kisMultiPricePort.fetchMultiPrice(listOf("093370"), "J", KisCallPriority.REALTIME_MARKET)
        }
        assertThat(tickSlot.captured.code).isEqualTo("093370")
        assertThat(tickSlot.captured.price).isEqualByComparingTo(BigDecimal("12340"))
        assertThat(tickSlot.captured.source).isEqualTo(ActiveStrategyCandleFeedPoller.SOURCE_KIS_POLLING)
        assertThat(tickSlot.captured.volume).isZero()
        verify(exactly = 1) { stopLossWatcher.onFallbackPriceTick(tickSlot.captured) }
    }

    @Test
    fun `WS tick이 신선하고 진행봉이 있으면 polling하지 않는다`() {
        every { activeStrategyCodeProvider.activeCodes() } returns setOf("005930")
        every { redisTemplate.hasKey("market:candle:current:code:005930:interval:1m") } returns true
        every { kisWebSocketBridge.lastTickAgeSeconds("005930") } returns 3L

        poller.poll()

        verify(exactly = 0) { kisMultiPricePort.fetchMultiPrice(any(), any(), any()) }
        verify(exactly = 0) { liveCandleUpdater.update(any()) }
        verify(exactly = 0) { stopLossWatcher.onFallbackPriceTick(any()) }
    }

    @Test
    fun `전략을 중지해 ACTIVE 종목이 없으면 polling하지 않고 누적 거래량 상태를 비운다`() {
        every { activeStrategyCodeProvider.activeCodes() } returns emptySet()

        poller.poll()

        verify(exactly = 0) { kisMultiPricePort.fetchMultiPrice(any(), any(), any()) }
        verify(exactly = 0) { liveCandleUpdater.update(any()) }
        verify(exactly = 0) { stopLossWatcher.onFallbackPriceTick(any()) }
    }

    @Test
    fun `poll 대상이 사이클 한도를 넘으면 다음 사이클에서 round-robin으로 이어서 조회한다`() {
        poller =
            ActiveStrategyCandleFeedPoller(
                activeStrategyCodeProvider = activeStrategyCodeProvider,
                kisMultiPricePort = kisMultiPricePort,
                liveCandleUpdater = liveCandleUpdater,
                positionStopLossTickWatcher = stopLossWatcher,
                kisWebSocketBridge = kisWebSocketBridge,
                redisTemplate = redisTemplate,
                enabled = true,
                staleAfterSeconds = 10L,
                maxCodesPerCycle = 2,
                clock = clock,
            )
        val requested = mutableListOf<List<String>>()

        every { activeStrategyCodeProvider.activeCodes() } returns linkedSetOf("000001", "000002", "000003")
        every { redisTemplate.hasKey(any()) } returns false
        every { kisWebSocketBridge.lastTickAgeSeconds(any()) } returns null
        every { liveCandleUpdater.update(any()) } just Runs
        every {
            kisMultiPricePort.fetchMultiPrice(any(), "J", KisCallPriority.REALTIME_MARKET)
        } answers {
            val codes = arg<List<String>>(0)
            requested += codes
            codes.map { code -> quote(code, close = "1000", accumulatedVolume = 1_000L) }
        }

        poller.poll()
        poller.poll()

        assertThat(requested).containsExactly(
            listOf("000001", "000002"),
            listOf("000003", "000001"),
        )
    }

    private fun quote(
        code: String,
        close: String,
        accumulatedVolume: Long,
    ): MultiPriceQuote =
        MultiPriceQuote(
            code = code,
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            previousClose = null,
            accumulatedVolume = accumulatedVolume,
            accumulatedTradeAmount = null,
            kospiKosdaqClsName = null,
            marketTreatmentClsName = null,
            hourClsCode = null,
        )
}
