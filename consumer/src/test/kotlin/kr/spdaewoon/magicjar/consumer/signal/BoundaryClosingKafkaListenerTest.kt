package kr.spdaewoon.magicjar.consumer.signal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * BoundaryClosingKafkaListener 단위 테스트 (ADR 0066 §2-1 A-1 + §7-1 검증).
 *
 * 테스트 매트릭스:
 *  1. 3m payload 파싱 → onBoundaryClosed THREE_MIN 호출
 *  2. 5m payload 파싱 → onBoundaryClosed FIVE_MIN 호출 + Candle 정확 매핑
 *  3. 1m payload 파싱 → onBoundaryClosed ONE_MIN 호출 + openRaw/closeRaw alias 매핑
 *  4. 1h payload 파싱 → onBoundaryClosed ONE_HOUR 호출
 *  5. 잘못된 payload (code 없음) → 예외 X (dispatch failure log만 + RSG 호출 0)
 */
class BoundaryClosingKafkaListenerTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val realtimeSignalGenerator = mockk<RealtimeSignalGenerator>(relaxed = true)
    private val listener = BoundaryClosingKafkaListener(realtimeSignalGenerator, objectMapper)

    @Test
    fun `3m payload 정상 → onBoundaryClosed THREE_MIN 호출`() {
        val payload =
            """
            {
                "code":"005930",
                "interval":"3m",
                "ts":1700000000,
                "open":"70000",
                "high":"71000",
                "low":"69500",
                "close":"70500",
                "volume":12345,
                "source":"KIS",
                "publishedAt":"2026-05-12T09:03:00Z"
            }
            """.trimIndent()

        val record = ConsumerRecord(KafkaTopics.Candle.M3_CLOSED, 0, 0L, "005930", payload)
        val codeSlot = slot<String>()
        val intervalSlot = slot<CandleInterval>()
        val tsSlot = slot<Instant>()
        val candleSlot = slot<Candle>()

        listener.on3m(record)

        verify {
            realtimeSignalGenerator.onBoundaryClosed(
                capture(codeSlot),
                capture(intervalSlot),
                capture(tsSlot),
                capture(candleSlot),
            )
        }
        assertThat(codeSlot.captured).isEqualTo("005930")
        assertThat(intervalSlot.captured).isEqualTo(CandleInterval.THREE_MIN)
        assertThat(tsSlot.captured).isEqualTo(Instant.fromEpochSeconds(1_700_000_000L))
        assertThat(candleSlot.captured.interval).isEqualTo(CandleInterval.THREE_MIN)
    }

    @Test
    fun `5m payload 정상 → onBoundaryClosed FIVE_MIN 호출 + Candle 매핑 정확`() {
        // ADR 0064 §4-5 BoundaryAggregator publish 정합 payload
        val payload =
            """
            {
                "code":"005930",
                "interval":"5m",
                "ts":1700000000,
                "open":"70000",
                "high":"71000",
                "low":"69500",
                "close":"70500",
                "volume":12345,
                "source":"KIS",
                "publishedAt":"2026-05-12T09:05:00Z"
            }
            """.trimIndent()

        val record = ConsumerRecord(KafkaTopics.Candle.M5_CLOSED, 0, 0L, "005930", payload)
        val codeSlot = slot<String>()
        val intervalSlot = slot<CandleInterval>()
        val tsSlot = slot<Instant>()
        val candleSlot = slot<Candle>()

        listener.on5m(record)

        verify {
            realtimeSignalGenerator.onBoundaryClosed(
                capture(codeSlot),
                capture(intervalSlot),
                capture(tsSlot),
                capture(candleSlot),
            )
        }
        assertThat(codeSlot.captured).isEqualTo("005930")
        assertThat(intervalSlot.captured).isEqualTo(CandleInterval.FIVE_MIN)
        assertThat(tsSlot.captured).isEqualTo(Instant.fromEpochSeconds(1_700_000_000L))
        assertThat(candleSlot.captured.closeRaw).isEqualByComparingTo(BigDecimal("70500"))
        assertThat(candleSlot.captured.volume).isEqualTo(12_345L)
        assertThat(candleSlot.captured.source).isEqualTo("KIS")
    }

    @Test
    fun `1m payload 정상 → onBoundaryClosed ONE_MIN 호출 + Raw 필드 매핑 정확`() {
        val payload =
            """
            {
                "code":"005930",
                "interval":"1m",
                "ts":"2026-05-29T04:10:00Z",
                "openRaw":"70000",
                "highRaw":"71000",
                "lowRaw":"69500",
                "closeRaw":"70500",
                "volume":12345
            }
            """.trimIndent()

        val record = ConsumerRecord(KafkaTopics.Candle.MINUTE_CLOSED, 0, 0L, "005930", payload)
        val codeSlot = slot<String>()
        val intervalSlot = slot<CandleInterval>()
        val tsSlot = slot<Instant>()
        val candleSlot = slot<Candle>()

        listener.on1m(record)

        verify {
            realtimeSignalGenerator.onBoundaryClosed(
                capture(codeSlot),
                capture(intervalSlot),
                capture(tsSlot),
                capture(candleSlot),
            )
        }
        assertThat(codeSlot.captured).isEqualTo("005930")
        assertThat(intervalSlot.captured).isEqualTo(CandleInterval.ONE_MIN)
        assertThat(tsSlot.captured).isEqualTo(Instant.parse("2026-05-29T04:10:00Z"))
        assertThat(candleSlot.captured.closeRaw).isEqualByComparingTo(BigDecimal("70500"))
        assertThat(candleSlot.captured.source).isEqualTo("KIS")
    }

    @Test
    fun `1h payload 정상 → onBoundaryClosed ONE_HOUR 호출`() {
        val payload =
            """
            {
                "code":"000660",
                "interval":"1h",
                "ts":1700003600,
                "open":"120000",
                "high":"121500",
                "low":"119000",
                "close":"120500",
                "volume":5000,
                "source":"KIS",
                "publishedAt":"2026-05-12T10:00:00Z"
            }
            """.trimIndent()

        val record = ConsumerRecord(KafkaTopics.Candle.H1_CLOSED, 0, 0L, "000660", payload)
        val codeSlot = slot<String>()
        val intervalSlot = slot<CandleInterval>()
        val tsSlot = slot<Instant>()
        val candleSlot = slot<Candle>()

        listener.on1h(record)

        verify {
            realtimeSignalGenerator.onBoundaryClosed(
                capture(codeSlot),
                capture(intervalSlot),
                capture(tsSlot),
                capture(candleSlot),
            )
        }
        assertThat(codeSlot.captured).isEqualTo("000660")
        assertThat(intervalSlot.captured).isEqualTo(CandleInterval.ONE_HOUR)
    }

    @Test
    fun `잘못된 payload (code 없음) → 예외 throw 0 + onBoundaryClosed 호출 0`() {
        val payload = """{"interval":"5m","ts":1700000000}"""
        val record = ConsumerRecord(KafkaTopics.Candle.M5_CLOSED, 0, 0L, "X", payload)
        val codeSlot = slot<String>()
        val intervalSlot = slot<CandleInterval>()
        val tsSlot = slot<Instant>()
        val candleSlot = slot<Candle>()

        listener.on5m(record)

        verify(exactly = 0) {
            realtimeSignalGenerator.onBoundaryClosed(
                capture(codeSlot),
                capture(intervalSlot),
                capture(tsSlot),
                capture(candleSlot),
            )
        }
    }
}
