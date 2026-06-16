package kr.spdaewoon.magicjar.consumer.signal

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Kafka boundary closing 토픽 listener (ADR 0066 §7-1 BoundaryClosingKafkaListener).
 *
 * 사용자 결정 (2026-05-12): A-1 Kafka boundary listener.
 *
 * ### 구독 토픽 (ADR 0064 §4-5 + KafkaTopicConfig 정합)
 *   market.candle.minute.closed.v1 → 1m interval 평가
 *   market.candle.3m.closed.v1     → 3m interval 평가
 *   market.candle.5m.closed.v1     → 5m interval 평가
 *   market.candle.15m.closed.v1    → 15m interval 평가
 *   market.candle.30m.closed.v1    → 30m interval 평가
 *   market.candle.90m.closed.v1    → 90m interval 평가
 *   market.candle.1h.closed.v1     → 1h interval 평가
 *   market.candle.4h.closed.v1     → 4h interval 평가
 *   market.candle.12h.closed.v1    → 12h interval 평가
 *
 * ### groupId 분리 (interval별 listener 독립 thread)
 * `magicjar-consumer-signal-{interval}` — 각 listener 독립 thread.
 * 같은 종목 5m·15m 동시 도달 시 (예: 09:30) — 다른 thread → strategy-eval-pool queue → 순차 처리.
 *
 * ### payload schema (BoundaryAggregator publish 정합 — ADR 0064 §4-5)
 *   { code, interval, ts, open, high, low, close, volume, source, publishedAt }
 *
 * ### partition by code (ADR 0066 §2-1 A-1 정합)
 * Kafka topic partition key = code → 같은 종목의 같은 interval boundary 순차 처리 보장.
 *
 * spec 인용: ADR 0066 §2-1 A-1 + §7-1 + §11-1 Phase 1 (5).
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class BoundaryClosingKafkaListener(
    private val realtimeSignalGenerator: RealtimeSignalGenerator,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(BoundaryClosingKafkaListener::class.java)

    /**
     * 1m closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-1m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.MINUTE_CLOSED],
        groupId = "magicjar-consumer-signal-1m",
    )
    fun on1m(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.ONE_MIN)
    }

    /**
     * 3m boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-3m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M3_CLOSED],
        groupId = "magicjar-consumer-signal-3m",
    )
    fun on3m(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.THREE_MIN)
    }

    /**
     * 5m boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-5m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M5_CLOSED],
        groupId = "magicjar-consumer-signal-5m",
    )
    fun on5m(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.FIVE_MIN)
    }

    /**
     * 15m boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-15m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M15_CLOSED],
        groupId = "magicjar-consumer-signal-15m",
    )
    fun on15m(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.FIFTEEN_MIN)
    }

    /**
     * 30m boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-30m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M30_CLOSED],
        groupId = "magicjar-consumer-signal-30m",
    )
    fun on30m(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.THIRTY_MIN)
    }

    /**
     * 90m boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-90m
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.M90_CLOSED],
        groupId = "magicjar-consumer-signal-90m",
    )
    fun on90m(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.NINETY_MIN)
    }

    /**
     * 1h boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-1h
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.H1_CLOSED],
        groupId = "magicjar-consumer-signal-1h",
    )
    fun on1h(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.ONE_HOUR)
    }

    /**
     * 4h boundary closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-4h
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.H4_CLOSED],
        groupId = "magicjar-consumer-signal-4h",
    )
    fun on4h(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.FOUR_HOUR)
    }

    /**
     * 12h final aggregate closing → RealtimeSignalGenerator 평가 위임.
     * groupId: magicjar-consumer-signal-12h
     */
    @KafkaListener(
        topics = [KafkaTopics.Candle.H12_CLOSED],
        groupId = "magicjar-consumer-signal-12h",
    )
    fun on12h(record: ConsumerRecord<String, String>) {
        dispatch(record, CandleInterval.TWELVE_HOUR)
    }

    /**
     * 공통 dispatch — payload deserialize 후 RealtimeSignalGenerator.onBoundaryClosed 호출.
     */
    private fun dispatch(
        record: ConsumerRecord<String, String>,
        interval: CandleInterval,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>

            val code = payload["code"]?.toString() ?: return
            val boundaryTs = parseTs(payload["ts"]) ?: return

            val candle =
                Candle(
                    id = null,
                    code = code,
                    interval = interval,
                    ts = boundaryTs,
                    openRaw = parseBd(payload["open"] ?: payload["openRaw"]) ?: BigDecimal.ZERO,
                    highRaw = parseBd(payload["high"] ?: payload["highRaw"]) ?: BigDecimal.ZERO,
                    lowRaw = parseBd(payload["low"] ?: payload["lowRaw"]) ?: BigDecimal.ZERO,
                    closeRaw = parseBd(payload["close"] ?: payload["closeRaw"]) ?: BigDecimal.ZERO,
                    openAdj = null,
                    highAdj = null,
                    lowAdj = null,
                    closeAdj = null,
                    volume = parseLong(payload["volume"]) ?: 0L,
                    source = payload["source"]?.toString() ?: "KIS",
                )

            log.debug(
                "BoundaryClosingKafkaListener 수신: interval={} code={} ts={} close={}",
                interval.code,
                code,
                boundaryTs,
                candle.closeRaw,
            )

            realtimeSignalGenerator.onBoundaryClosed(code, interval, boundaryTs, candle)
        } catch (e: Exception) {
            log.error(
                "BoundaryClosingKafkaListener dispatch 실패: interval={} offset={} err={}",
                interval.code,
                record.offset(),
                e.message,
            )
        }
    }

    private fun parseTs(value: Any?): Instant? =
        when (value) {
            null -> null
            is Number -> Instant.fromEpochSeconds(value.toLong())
            is String ->
                value.toLongOrNull()?.let { Instant.fromEpochSeconds(it) }
                    ?: runCatching { Instant.parse(value) }.getOrNull()
            else -> null
        }

    private fun parseBd(value: Any?): BigDecimal? =
        when (value) {
            null -> null
            is BigDecimal -> value
            is Number -> BigDecimal.valueOf(value.toDouble())
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

    private fun parseLong(value: Any?): Long? =
        when (value) {
            null -> null
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
}
