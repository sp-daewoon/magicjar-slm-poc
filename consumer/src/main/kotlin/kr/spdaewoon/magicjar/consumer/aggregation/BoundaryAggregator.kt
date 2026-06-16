package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.cache.RedisCandleCache
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.BoundaryDetector
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleAggregator
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.FIFTEEN_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.FIVE_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.FOUR_HOUR
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.NINETY_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_HOUR
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.ONE_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.THIRTY_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.THREE_MIN
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval.TWELVE_HOUR
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.Instant as KtInstant

/**
 * KIS WS 1m tick 마감 시 boundary 검사 + 3m·5m·15m·30m·90m·1h·4h·12h bar 합산 + 갱신.
 *
 * ADR 0064 B-1 채택 (in-process boundary 검사):
 *   KisWebSocketBridge H0STCNT0 체결 수신 → TickToMinuteAggregator 1m closing 처리 직후
 *   → BoundaryAggregator.onTickClosed(code, tickTs) 호출
 *
 * KIS-first raw 출처 (헌법 §3 의무):
 *   H0STCNT0 체결가 cols[1] = STCK_CNTG_HOUR (HHMMSS) — boundary 검사 트리거 시각.
 *   raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_krx() 함수 [1] = STCK_CNTG_HOUR
 *   _workspace/baseline/v3.2.2/03-use-sequences.md §시나리오 3 R5 인용.
 *
 * 흐름 (ADR 0064 §4-3):
 *   1m tick 마감 ts → BoundaryDetector.isBoundaryClosed(ts, target) → 도달한 interval 각각:
 *     1. DB 1m fetch (boundary 시작~마감분 inclusive — NXT 12h=08:00~19:59 720건)
 *     2. CandleAggregator.aggregate(1m list, target) → bar 생성
 *     3. RedisCandleCache.putBoundaryCandle(code, interval, bar) (ADR 0064 A-1)
 *     4. ConsumerCandleJpaRepository.insertOrUpdateIfGapfill (gapfill row 교정 + 멱등)
 *     5. Kafka publish market.candle.{interval}.closed.v1 (ADR 0064 C-1)
 *
 * 멱등 가드 (ADR 0064 §4-6):
 *   in-process lastBoundaryProcessed: Map<Pair<code, interval>, LocalDate(KST)>
 *   같은 boundary 분에 여러 tick 도달 시 첫 번째 처리만 실행.
 *   컨테이너 재기동 시 map 손실 → DB conditional UPSERT로 멱등 보장.
 *
 * 헌법 §6 JPA default fun 함정: ConsumerCandleJpaRepository.insertOrUpdateIfGapfill는 @Query 사용
 *   (abstract body 없음) — 함정 회피 확인.
 */
@Component
class BoundaryAggregator(
    private val candleJpaRepository: ConsumerCandleJpaRepository,
    private val redisCandleCache: RedisCandleCache,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(BoundaryAggregator::class.java)

    companion object {
        private val TARGET_INTERVALS =
            listOf(
                THREE_MIN,
                FIVE_MIN,
                FIFTEEN_MIN,
                THIRTY_MIN,
                NINETY_MIN,
                ONE_HOUR,
                FOUR_HOUR,
                TWELVE_HOUR,
            )
        private val KST = ZoneId.of("Asia/Seoul")

        /** interval → Kafka topic 매핑 (ADR 0064 C-1 §4-5) */
        private val INTERVAL_TOPIC =
            mapOf(
                THREE_MIN to KafkaTopics.Candle.M3_CLOSED,
                FIVE_MIN to KafkaTopics.Candle.M5_CLOSED,
                FIFTEEN_MIN to KafkaTopics.Candle.M15_CLOSED,
                THIRTY_MIN to KafkaTopics.Candle.M30_CLOSED,
                NINETY_MIN to KafkaTopics.Candle.M90_CLOSED,
                ONE_HOUR to KafkaTopics.Candle.H1_CLOSED,
                FOUR_HOUR to KafkaTopics.Candle.H4_CLOSED,
                TWELVE_HOUR to KafkaTopics.Candle.H12_CLOSED,
            )
    }

    /**
     * in-process 멱등 가드 (ADR 0064 §4-6):
     * Pair(code, interval) → 마지막 처리된 날짜(KST) + 마감 분(minOfDay)
     * 같은 boundary 분에 여러 tick 도달 시 첫 번째 처리만 실행.
     */
    private val lastProcessedBoundaryMin: ConcurrentHashMap<Pair<String, CandleInterval>, Pair<LocalDate, Int>> =
        ConcurrentHashMap()

    /**
     * 1m tick 마감 시 호출 — 합산 target interval 각각 boundary 검사.
     *
     * 호출 시점: TickToMinuteAggregator.aggregateOneMinuteCandle() 완료 직후
     * (KisWebSocketBridge.handleTickData → TickToMinuteAggregator → onTickClosed 순서로 확장).
     *
     * @param code 종목코드 (KIS 6자리, 예: "000660")
     * @param tickTs 1m tick 마감 ts (Instant — boundary 검사 기준)
     */
    @Transactional
    fun onTickClosed(
        code: String,
        tickTs: Instant,
    ) {
        // kotlinx.datetime.Instant로 변환 (BoundaryDetector 인터페이스)
        val ktInstant = KtInstant.fromEpochSeconds(tickTs.epochSecond)

        val kstDate = tickTs.atZone(KST).toLocalDate()

        for (target in TARGET_INTERVALS) {
            try {
                processInterval(code, ktInstant, kstDate, target)
            } catch (e: Exception) {
                log.error(
                    "BoundaryAggregator interval 처리 실패: code={} interval={} ts={} err={}",
                    code,
                    target.code,
                    tickTs,
                    e.message,
                )
            }
        }
    }

    private fun processInterval(
        code: String,
        tickTs: KtInstant,
        kstDate: LocalDate,
        target: CandleInterval,
    ) {
        // 1. boundary 마감 여부 검사
        if (!BoundaryDetector.isBoundaryClosed(tickTs, target)) return

        // 2. 멱등 가드 — 같은 boundary 분 중복 처리 방지
        val tickLdt =
            java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(tickTs.epochSeconds),
                KST,
            )
        val minOfDay = tickLdt.hour * 60 + tickLdt.minute
        val guardKey = Pair(code, target)
        val lastProcessed = lastProcessedBoundaryMin[guardKey]
        if (lastProcessed != null && lastProcessed.first == kstDate && lastProcessed.second == minOfDay) {
            log.debug(
                "BoundaryAggregator 중복 skip: code={} interval={} date={} min={}",
                code,
                target.code,
                kstDate,
                minOfDay,
            )
            return
        }

        log.info(
            "BoundaryAggregator boundary 마감 검출: code={} interval={} ts={}",
            code,
            target.code,
            tickTs,
        )

        // 3. boundary 시작~마감분 1m fetch (DB)
        val boundaryStartTs = BoundaryDetector.boundaryStartTs(tickTs, target)
        val boundaryStartInstant = Instant.ofEpochSecond(boundaryStartTs.epochSeconds)
        val boundaryCloseInstant = Instant.ofEpochSecond(tickTs.epochSeconds)

        val oneMinCandles = fetchBoundaryOneMinutes(code, boundaryStartInstant, boundaryCloseInstant)
        if (oneMinCandles.isEmpty()) {
            log.warn(
                "BoundaryAggregator 1m data 없음: code={} interval={} boundaryStart={}",
                code,
                target.code,
                boundaryStartInstant,
            )
            return
        }

        // 4. CandleAggregator.aggregate(1m list, target) → bar 생성
        val aggregated = CandleAggregator.aggregate(oneMinCandles, target)
        if (aggregated.isEmpty()) {
            log.warn(
                "BoundaryAggregator 합산 결과 없음: code={} interval={} 1mCount={}",
                code,
                target.code,
                oneMinCandles.size,
            )
            return
        }
        // 마지막 (= boundary 마감) bar 사용
        val bar = aggregated.last()

        // 5. Redis candle:current:{code}:{interval} 갱신 (ADR 0064 A-1)
        redisCandleCache.putBoundaryCandle(code, target, bar)

        // 6. DB UPSERT (gapfill row 교정 + idempotent)
        insertBarToDb(bar, target)

        // 7. Kafka publish market.candle.{interval}.closed.v1 (ADR 0064 C-1)
        publishToKafka(code, target, bar)

        // 8. 멱등 가드 갱신
        lastProcessedBoundaryMin[guardKey] = Pair(kstDate, minOfDay)

        log.info(
            "BoundaryAggregator 완료: code={} interval={} ts={} close={}",
            code,
            target.code,
            bar.ts,
            bar.closeRaw,
        )
    }

    /**
     * boundary 내 1m candle DB fetch.
     * boundary 시작 ts부터 boundary 마감 1m ts까지의 candle을 ts ASC로 조회.
     * (DB는 TickToMinuteAggregator가 1m closing 시 INSERT → 이 시점에는 이미 존재)
     */
    private fun fetchBoundaryOneMinutes(
        code: String,
        boundaryStartInstant: Instant,
        boundaryCloseInstant: Instant,
    ): List<Candle> {
        // ConsumerCandleJpaRepository.findByCodeAndIntervalOrderByTsAsc 전량 조회 후 boundary 필터
        // ADR 0064 §4-3 + ADR 0105: 해당 boundary 내 1m candles만 사용.
        val allOneMin =
            candleJpaRepository.findByCodeAndIntervalOrderByTsAsc(code, "1m")

        return allOneMin
            .filter { entity ->
                !entity.ts.isBefore(boundaryStartInstant) &&
                    !entity.ts.isAfter(boundaryCloseInstant)
            }.mapNotNull { entity -> entity.toDomain() }
    }

    private fun insertBarToDb(
        bar: Candle,
        interval: CandleInterval,
    ) {
        try {
            val count =
                candleJpaRepository.insertOrUpdateIfGapfill(
                    code = bar.code,
                    interval = interval.code,
                    ts = Instant.ofEpochSecond(bar.ts.epochSeconds),
                    openRaw = bar.openRaw,
                    highRaw = bar.highRaw,
                    lowRaw = bar.lowRaw,
                    closeRaw = bar.closeRaw,
                    volume = bar.volume,
                    source = bar.source,
                )
            log.debug(
                "BoundaryAggregator DB UPSERT: code={} interval={} ts={} affected={}",
                bar.code,
                interval.code,
                bar.ts,
                count,
            )
        } catch (e: Exception) {
            log.error(
                "BoundaryAggregator DB UPSERT 실패: code={} interval={} ts={} err={}",
                bar.code,
                interval.code,
                bar.ts,
                e.message,
            )
        }
    }

    /**
     * Kafka market.candle.{interval}.closed.v1 publish.
     *
     * payload schema (ADR 0064 §4-5):
     * { code, interval, ts, open, high, low, close, volume, source, publishedAt }
     */
    private fun publishToKafka(
        code: String,
        interval: CandleInterval,
        bar: Candle,
    ) {
        val topic = INTERVAL_TOPIC[interval] ?: return
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to code,
                        "interval" to interval.code,
                        "ts" to Instant.ofEpochSecond(bar.ts.epochSeconds).toString(),
                        "open" to bar.openRaw.toPlainString(),
                        "high" to bar.highRaw.toPlainString(),
                        "low" to bar.lowRaw.toPlainString(),
                        "close" to bar.closeRaw.toPlainString(),
                        "volume" to bar.volume,
                        "source" to bar.source,
                        "publishedAt" to
                            java.time.Instant
                                .now()
                                .toString(),
                    ),
                )
            kafkaTemplate.send(topic, code, payload)
            log.debug("BoundaryAggregator Kafka publish: topic={} code={} ts={}", topic, code, bar.ts)
        } catch (e: Exception) {
            log.error(
                "BoundaryAggregator Kafka publish 실패: topic={} code={} err={}",
                topic,
                code,
                e.message,
            )
        }
    }

    /** ConsumerCandleJpaEntity → domain Candle 변환 (helper — 헌법 §6 caller 클래스 내부) */
    private fun kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaEntity.toDomain(): Candle? =
        try {
            Candle(
                id = id,
                code = code,
                interval = ONE_MIN,
                ts = KtInstant.fromEpochSeconds(ts.epochSecond),
                openRaw = openRaw ?: BigDecimal.ZERO,
                highRaw = highRaw ?: BigDecimal.ZERO,
                lowRaw = lowRaw ?: BigDecimal.ZERO,
                closeRaw = closeRaw ?: BigDecimal.ZERO,
                openAdj = openAdj,
                highAdj = highAdj,
                lowAdj = lowAdj,
                closeAdj = closeAdj,
                volume = volume ?: 0L,
                source = source,
            )
        } catch (e: Exception) {
            log.warn("BoundaryAggregator entity→Candle 변환 실패: id={} err={}", id, e.message)
            null
        }
}
