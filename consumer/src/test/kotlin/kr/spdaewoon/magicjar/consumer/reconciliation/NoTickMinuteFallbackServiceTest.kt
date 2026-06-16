package kr.spdaewoon.magicjar.consumer.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.consumer.aggregation.BoundaryAggregator
import kr.spdaewoon.magicjar.consumer.market.LiveCandleUpdater
import kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

class NoTickMinuteFallbackServiceTest {
    private val candleRepository = mockk<CandleRepository>()
    private val rangePort = mockk<KisOneMinuteCandleRangePort>()
    private val boundaryAggregator = mockk<BoundaryAggregator>(relaxed = true)
    private val liveCandleUpdater = mockk<LiveCandleUpdater>(relaxed = true)
    private val stopLossWatcher = mockk<PositionStopLossTickWatcher>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private fun service(): NoTickMinuteFallbackService =
        NoTickMinuteFallbackService(
            candleRepository = candleRepository,
            kisOneMinuteCandleRangePort = rangePort,
            boundaryAggregator = boundaryAggregator,
            liveCandleUpdater = liveCandleUpdater,
            positionStopLossTickWatcher = stopLossWatcher,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            enabled = true,
            dedupeSeconds = 60L,
        )

    @Test
    fun `no tick fallback은 REST 1m gap을 저장하고 최신 닫힌 봉 1건만 Kafka 발행한다`() {
        val lastTs = Instant.now().minusSeconds(180)
        val savedSlot = slot<List<Candle>>()
        val payloadSlot = slot<String>()
        val targetToSlot = slot<Instant>()
        val tickSlot = slot<TickEvent>()

        every { candleRepository.findMaxTsByCodeAndInterval("093370", CandleInterval.ONE_MIN) } returns lastTs
        every { rangePort.fetchOneMinuteCandlesBetween("093370", lastTs.plusSeconds(60), capture(targetToSlot)) } answers {
            listOf(
                candle("093370", lastTs.plusSeconds(60)),
                candle("093370", targetToSlot.captured),
            )
        }
        every { candleRepository.saveAll(capture(savedSlot)) } returns 2

        val result = service().runFallbackNow(code = "093370", ageSeconds = 148)

        assertThat(result.fetched).isEqualTo(2)
        assertThat(result.inserted).isEqualTo(2)
        assertThat(result.published).isTrue()
        assertThat(result.currentSeeded).isTrue()
        assertThat(savedSlot.captured).allSatisfy { assertThat(it.source).isEqualTo("KIS-NOTICK-FALLBACK") }
        verify(exactly = 1) { liveCandleUpdater.update(capture(tickSlot)) }
        verify(exactly = 1) { stopLossWatcher.onFallbackPriceTick(tickSlot.captured) }
        assertThat(tickSlot.captured.code).isEqualTo("093370")
        assertThat(tickSlot.captured.source).isEqualTo("KIS-NOTICK-FALLBACK")
        assertThat(tickSlot.captured.stale).isTrue()
        verify(exactly = 1) {
            kafkaTemplate.send(KafkaTopics.Candle.MINUTE_CLOSED, "093370", capture(payloadSlot))
        }
        val payload = objectMapper.readValue(payloadSlot.captured, Map::class.java)
        assertThat(payload["code"]).isEqualTo("093370")
        assertThat(payload["interval"]).isEqualTo("1m")
        assertThat(payload["source"]).isEqualTo("KIS-NOTICK-FALLBACK")
        assertThat(payload["reason"]).isEqualTo("ws-no-tick-fallback")
        verify(exactly = 1) { boundaryAggregator.onTickClosed("093370", result.latestTs!!) }
    }

    @Test
    fun `REST gap이 없어도 DB 최신 1분봉으로 Redis current와 손절 fallback tick을 seed한다`() {
        val lastTs = Instant.now().plusSeconds(60)
        val latest = candle("093370", Instant.parse("2026-06-16T04:42:00Z"))
        val tickSlot = slot<TickEvent>()

        every { candleRepository.findMaxTsByCodeAndInterval("093370", CandleInterval.ONE_MIN) } returns lastTs
        every { candleRepository.findLatestByCodeAndInterval("093370", CandleInterval.ONE_MIN, 1) } returns listOf(latest)

        val result = service().runFallbackNow(code = "093370", ageSeconds = 180)

        assertThat(result.fetched).isZero()
        assertThat(result.inserted).isZero()
        assertThat(result.published).isFalse()
        assertThat(result.currentSeeded).isTrue()
        assertThat(result.latestTs).isEqualTo(latest.ts.toJavaInstant())
        verify(exactly = 0) { rangePort.fetchOneMinuteCandlesBetween(any(), any(), any()) }
        verify(exactly = 0) { candleRepository.saveAll(any()) }
        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any(), any()) }
        verify(exactly = 0) { boundaryAggregator.onTickClosed(any(), any()) }
        verify(exactly = 1) { liveCandleUpdater.update(capture(tickSlot)) }
        verify(exactly = 1) { stopLossWatcher.onFallbackPriceTick(tickSlot.captured) }
        assertThat(tickSlot.captured.code).isEqualTo("093370")
        assertThat(tickSlot.captured.price).isEqualByComparingTo("100")
        assertThat(tickSlot.captured.source).isEqualTo("KIS-NOTICK-FALLBACK")
        assertThat(tickSlot.captured.stale).isTrue()
    }

    private fun candle(
        code: String,
        ts: Instant,
    ): Candle {
        val price = BigDecimal("100")
        return Candle(
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = KtInstant.fromEpochMilliseconds(ts.toEpochMilli()),
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
