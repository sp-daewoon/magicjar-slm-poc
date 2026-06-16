package kr.spdaewoon.magicjar.consumer.market

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.aggregation.BoundaryAggregator
import kr.spdaewoon.magicjar.consumer.ingestion.ActiveStrategyCodeProvider
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * TickToMinuteAggregator 분 경계 race fix 회귀 테스트 (2026-06-04 dogfooding 결함 3, P2).
 *
 * 검증:
 *   (a) 분 경계에서 새 분 tick 유입 시 직전 분 캔들 ts·OHLC 오염 0 (미래 분 bar 복원 후 skip)
 *   (b) 같은 ts 이중 발행 0 (insertOrUpdateIfGapfill rows==0 이면 publish skip)
 *   (c) tick 없는 분은 발행 0 (RENAME 실패 → skip, mock 합성 0)
 *   (d) RENAME 후 신규 tick 유실 0 (복원 시 live 키 비어있을 때만 되돌림)
 */
class TickToMinuteAggregatorTest {
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val candleRepository = mockk<ConsumerCandleJpaRepository>()
    private val kisWebSocketBridge = mockk<KisWebSocketBridge>()
    private val activeStrategyCodeProvider = mockk<ActiveStrategyCodeProvider>()
    private val boundaryAggregator = mockk<BoundaryAggregator>(relaxed = true)
    private val hashOps = mockk<HashOperations<String, String, String>>(relaxed = true)
    private val objectMapper = jacksonObjectMapper()

    // TransactionTemplate — mock txManager 위 실제 인스턴스 (execute 콜백을 그대로 실행).
    private val txManager =
        mockk<PlatformTransactionManager>(relaxed = true) {
            every { getTransaction(any()) } returns SimpleTransactionStatus()
        }
    private val transactionTemplate = TransactionTemplate(txManager)

    // 고정 시계: 2026-06-04 14:23:00 KST = 05:23:00Z. closingTs 기대값 = 05:22:00Z, nowMinute = 05:23:00Z.
    private val fixedNow = Instant.parse("2026-06-04T05:23:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val closingTs = Instant.parse("2026-06-04T05:22:00Z")
    private val nowMinute = Instant.parse("2026-06-04T05:23:00Z")

    private val aggregator =
        TickToMinuteAggregator(
            redisTemplate = redisTemplate,
            kafkaTemplate = kafkaTemplate,
            candleRepository = candleRepository,
            objectMapper = objectMapper,
            kisWebSocketBridge = kisWebSocketBridge,
            activeStrategyCodeProvider = activeStrategyCodeProvider,
            boundaryAggregator = boundaryAggregator,
            transactionTemplate = transactionTemplate,
            clock = clock,
        )

    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        every { kisWebSocketBridge.subscribedCodes() } returns setOf("005930")
        every { activeStrategyCodeProvider.activeCodes() } returns emptySet()
    }

    private fun barHash(
        startTs: Instant,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: Long,
        source: String = "KIS",
    ): Map<String, String> =
        mapOf(
            "interval" to "1m",
            "open" to open,
            "high" to high,
            "low" to low,
            "close" to close,
            "volume" to volume.toString(),
            "startTs" to startTs.toString(),
            "ts" to startTs.toString(),
            "source" to source,
        )

    @Test
    fun `정상 분 마감 — 닫힌 분 bar 1회 INSERT 및 publish`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } returns true
        every { hashOps.entries(snapKey) } returns
            barHash(closingTs, "100", "110", "95", "105", 1000L)
        every {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", closingTs, any(), any(), any(), any(), 1000L, "KIS")
        } returns 1

        aggregator.aggregate()

        val tsSlot = slot<Instant>()
        verify(exactly = 1) {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", capture(tsSlot), any(), any(), any(), any(), 1000L, "KIS")
        }
        // (deterministic) 발행 ts = closingTs (스케줄 시각 기준 직전 분), 해시 ts 무관하게 일관
        assertThat(tsSlot.captured).isEqualTo(closingTs)
        verify(exactly = 1) { kafkaTemplate.send(KafkaTopics.Candle.MINUTE_CLOSED, "005930", any()) }
    }

    @Test
    fun `같은 ts 이중 발행 차단 — UPSERT rows 0이면 publish skip`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } returns true
        every { hashOps.entries(snapKey) } returns
            barHash(closingTs, "100", "110", "95", "105", 1000L)
        // 이미 DB 에 존재 → conditional UPSERT → rows 0
        every {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", closingTs, any(), any(), any(), any(), any(), "KIS")
        } returns 0

        aggregator.aggregate()

        // 중복 → Kafka 발행 0
        verify(exactly = 0) { kafkaTemplate.send(any(), any<String>(), any()) }
    }

    @Test
    fun `polling source 분 마감 — source 를 DB와 Kafka payload에 보존`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } returns true
        every { hashOps.entries(snapKey) } returns
            barHash(closingTs, "100", "110", "95", "105", 1000L, source = "KIS-POLLING")
        every {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", closingTs, any(), any(), any(), any(), 1000L, "KIS-POLLING")
        } returns 1

        aggregator.aggregate()

        verify(exactly = 1) {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", closingTs, any(), any(), any(), any(), 1000L, "KIS-POLLING")
        }
        val payloadSlot = slot<String>()
        verify(exactly = 1) { kafkaTemplate.send(KafkaTopics.Candle.MINUTE_CLOSED, "005930", capture(payloadSlot)) }
        assertThat(payloadSlot.captured).contains("\"source\":\"KIS-POLLING\"")
    }

    @Test
    fun `tick 없는 분 — RENAME 실패 시 발행 0 (mock 합성 없음)`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        // source 키 없음 → renameIfAbsent 예외 (Spring RENAMENX no such key)
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } throws RuntimeException("ERR no such key")

        aggregator.aggregate()

        verify(exactly = 0) { candleRepository.insertOrUpdateIfGapfill(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { kafkaTemplate.send(any(), any<String>(), any()) }
    }

    @Test
    fun `분 경계 race — 미래 분 bar 스냅샷 시 직전 분 오염 0 및 live 복원 (유실 0)`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        // 경계 직전 새 분(05:23) tick 이 live 키를 rotate → 스냅샷이 미래 분 bar 를 담음
        val futureBar = barHash(nowMinute, "200", "205", "198", "201", 50L)
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } returns true
        every { hashOps.entries(snapKey) } returns futureBar
        // RENAME 후 신규 tick 이 아직 live 를 채우지 않음 → 복원 대상
        every { redisTemplate.hasKey(liveKey) } returns false

        aggregator.aggregate()

        // 미래 분 bar 는 닫힌 분이 아니므로 INSERT·publish 0 (직전 분 ts 로 오염 발행 금지)
        verify(exactly = 0) { candleRepository.insertOrUpdateIfGapfill(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { kafkaTemplate.send(any(), any<String>(), any()) }
        // live 키로 복원되어야 함 (다음 사이클이 05:23 분을 처리, tick 유실 0)
        verify(exactly = 1) { hashOps.putAll(liveKey, futureBar) }
        verify(exactly = 1) { redisTemplate.expire(liveKey, Duration.ofMinutes(2)) }
        // 스냅샷 임시 키는 정리
        verify(exactly = 1) { redisTemplate.delete(snapKey) }
    }

    @Test
    fun `분 경계 race — 신규 tick 이 이미 live 채운 경우 복원하지 않음 (OHLC 충돌 회피)`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        val futureBar = barHash(nowMinute, "200", "205", "198", "201", 50L)
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } returns true
        every { hashOps.entries(snapKey) } returns futureBar
        // RENAME 후 신규 tick 이 이미 같은 분을 누적 중 → live 키 존재 → 복원 금지
        every { redisTemplate.hasKey(liveKey) } returns true

        aggregator.aggregate()

        verify(exactly = 0) { hashOps.putAll(liveKey, any()) }
        verify(exactly = 0) { kafkaTemplate.send(any(), any<String>(), any()) }
        verify(exactly = 1) { redisTemplate.delete(snapKey) }
    }

    @Test
    fun `과거 분 bar — 해당 분의 실제 ts 로 발행 (분 누락 보정)`() {
        val liveKey = "market:candle:current:code:005930:interval:1m"
        val snapKey = "candle:closing:005930:1m:${closingTs.epochSecond}"
        // 직전 사이클에 발행 못 한 과거 분(05:21) bar 가 스냅샷에 남음
        val pastTs = Instant.parse("2026-06-04T05:21:00Z")
        every { redisTemplate.renameIfAbsent(liveKey, snapKey) } returns true
        every { hashOps.entries(snapKey) } returns barHash(pastTs, "90", "92", "89", "91", 300L)
        every {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", pastTs, any(), any(), any(), any(), 300L, "KIS")
        } returns 1

        aggregator.aggregate()

        // 과거 분은 그 bar 의 실제 ts(05:21) 로 발행 — closingTs(05:22) 로 잘못 라벨링하지 않음
        verify(exactly = 1) {
            candleRepository.insertOrUpdateIfGapfill("005930", "1m", pastTs, any(), any(), any(), any(), 300L, "KIS")
        }
        val payloadSlot = slot<String>()
        verify(exactly = 1) { kafkaTemplate.send(KafkaTopics.Candle.MINUTE_CLOSED, "005930", capture(payloadSlot)) }
        assertThat(payloadSlot.captured).contains("\"ts\":\"2026-06-04T05:21:00Z\"")
    }

    @Test
    fun `구독 종목 0건 — 아무 동작 안 함`() {
        every { kisWebSocketBridge.subscribedCodes() } returns emptySet()

        aggregator.aggregate()

        verify(exactly = 0) { redisTemplate.renameIfAbsent(any(), any()) }
        verify(exactly = 0) { boundaryAggregator.onTickClosed(any(), any()) }
    }
}
