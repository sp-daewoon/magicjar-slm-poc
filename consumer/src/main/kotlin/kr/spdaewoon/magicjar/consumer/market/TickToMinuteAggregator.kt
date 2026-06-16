package kr.spdaewoon.magicjar.consumer.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.aggregation.BoundaryAggregator
import kr.spdaewoon.magicjar.consumer.ingestion.ActiveStrategyCodeProvider
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 1분봉 closing 어그리게이터 (ADR 0029 §2-3 정합).
 *
 * @Scheduled(cron = "0 * * * * ?") 매 분 0초 실행:
 * 1. KIS WS 구독 중인 모든 종목 → Redis `candle:current:{code}:1m` 를 원자 스냅샷(RENAME)으로 분리
 * 2. 스냅샷의 OHLCV → candle DB upsert (gapfill row 교정 + idempotent)
 * 3. INSERT/UPDATE 가 실제 반영될 때만 Kafka `market.candle.minute.closed.v1` publish (중복 발행 차단)
 * 4. 스냅샷 임시 키 DEL (다음 분봉 시작 준비)
 * 5. BoundaryAggregator.onTickClosed(code, closingTs) 호출 (ADR 0064 B-1 — 합산 interval boundary 검사)
 *
 * PR-9b market-data 담당 (dispatch §3-2-F — KIS WS handler 연관성).
 * ADR 0064 §4-3 BoundaryAggregator 연동 — 1m closing 완료 직후 boundary 검사 trigger.
 *
 * 분 경계 race fix (2026-06-04 dogfooding 결함 3, P2):
 *   - closingTs 는 스케줄 실행 시각(now) 기준 직전 분(floor) 으로 deterministic 산출.
 *     해시의 ts 필드(마지막 tick 의 분)에 의존하지 않는다.
 *   - 해시 read 와 reset 사이 WS tick race 차단: `candle:current:{code}:1m` 를 임시 키로
 *     원자 RENAME 하여 스냅샷을 분리한다. RENAME 직후 신규 tick 은 빈 live 키에 자연 누적되어
 *     유실 0. 스냅샷은 임시 키에서만 읽으므로 동시 갱신 오염 0.
 *   - 스냅샷의 bar ts 를 기대 closingTs 와 대조: 미래 분(boundary 직전 새 분 tick 으로 rotate 된 bar)
 *     이면 live 키로 복원 후 skip(다음 사이클이 처리) — bar 유실 0. 과거 분이면 그 ts 로 1회 발행.
 *   - 같은 ts 이중 발행 차단: insertOrUpdateIfGapfill(rows==1, 신규 INSERT 또는 gapfill 교정) 일 때만 publish.
 *     이미 처리된 실제 row는 rows=0으로 Kafka 재발행을 막는다.
 *
 * KIS-first raw 출처 (헌법 §3 의무):
 *   H0STCNT0 체결가 STCK_CNTG_HOUR (HHMMSS) → 1m closing ts 산출 기준.
 *   raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   _workspace/baseline/v3.2.2/03-use-sequences.md §시나리오 3 R5 인용.
 */
@Component
class TickToMinuteAggregator(
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val candleRepository: ConsumerCandleJpaRepository,
    private val objectMapper: ObjectMapper,
    private val kisWebSocketBridge: KisWebSocketBridge,
    private val activeStrategyCodeProvider: ActiveStrategyCodeProvider,
    private val boundaryAggregator: BoundaryAggregator,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(TickToMinuteAggregator::class.java)

    /**
     * 매 분 0초 실행 — 1분봉 closing → DB UPSERT + Kafka publish.
     * KIS WS 구독 종목 전체 처리.
     */
    @Scheduled(cron = "0 * * * * ?")
    fun aggregate() {
        val subscribedCodes = kisWebSocketBridge.subscribedCodes() + activeStrategyCodeProvider.activeCodes()
        if (subscribedCodes.isEmpty()) return

        // deterministic: 스케줄 실행 시각(now) 기준 방금 닫힌 분봉의 시작 시각.
        // 해시 ts 필드가 아니라 시계에서 산출 — 분 누락·ts 불일치 방지.
        val nowMinute = Instant.now(clock).truncatedTo(ChronoUnit.MINUTES)
        val closingTs = nowMinute.minusSeconds(60)

        subscribedCodes.forEach { code ->
            try {
                aggregateOneMinuteCandle(code, closingTs, nowMinute)
                // ADR 0064 B-1 — 1m closing 완료 직후 boundary 검사 trigger.
                // deterministic closingTs 로 trigger (해시 ts 무관). BoundaryAggregator 는
                // boundary 분에서만 동작하고 자체 멱등 가드로 중복을 skip 하므로 매 사이클 호출해도 안전.
                boundaryAggregator.onTickClosed(code, closingTs)
            } catch (e: Exception) {
                log.error("1m closing 처리 실패: code={} error={}", code, e.message)
            }
        }
    }

    /**
     * 단일 종목 1m closing 처리.
     */
    private fun aggregateOneMinuteCandle(
        code: String,
        closingTs: Instant,
        nowMinute: Instant,
    ) {
        val liveKey = RedisKeyRegistry.Market.candleCurrent(code, "1m")
        val snapshotKey = "candle:closing:$code:1m:${closingTs.epochSecond}"

        // 원자 스냅샷: live → 임시 키 RENAME. RENAME 후 신규 tick 은 빈 live 키에 누적(유실 0).
        // live 키가 없으면(tick 0건) RENAME 이 실패 → null 반환되며 그대로 skip.
        val renamed =
            try {
                redisTemplate.renameIfAbsent(liveKey, snapshotKey)
            } catch (e: Exception) {
                // source 키 없음 등 — tick 0건 빈 구간. mock 합성 금지(candle-data-policy-v2 LOCK).
                log.debug("1m closing: code={} live 키 없음 (장외 또는 tick 0건): {}", code, e.message)
                return
            }
        if (renamed != true) {
            // 임시 키가 이미 존재(직전 사이클 미정리) 또는 source 없음 — 안전하게 skip.
            log.debug("1m closing: code={} 스냅샷 RENAME skip (source 없음 또는 임시키 잔존)", code)
            return
        }

        try {
            val hash = redisTemplate.opsForHash<String, String>().entries(snapshotKey)
            if (hash.isEmpty()) {
                log.debug("1m closing: code={} 스냅샷 비어있음", code)
                return
            }

            val barTs = parseBarTs(hash) ?: closingTs

            // 미래/현재 분 bar: boundary 직전 새 분 tick 으로 rotate 된 live bar 를 스냅샷한 경우.
            // 이 bar 는 방금 닫힌 분이 아니므로 발행 금지. live 키로 복원 후 다음 사이클이 처리(유실 0).
            if (barTs >= nowMinute) {
                restoreSnapshot(liveKey, snapshotKey, hash)
                log.debug(
                    "1m closing: code={} 스냅샷 bar 가 현재/미래 분(barTs={} >= nowMinute={}) — 복원 후 skip",
                    code,
                    barTs,
                    nowMinute,
                )
                return
            }

            val openRaw = hash["open"]?.toBigDecimalOrNull() ?: return
            val highRaw = hash["high"]?.toBigDecimalOrNull() ?: return
            val lowRaw = hash["low"]?.toBigDecimalOrNull() ?: return
            val closeRaw = hash["close"]?.toBigDecimalOrNull() ?: return
            val volume = hash["volume"]?.toLongOrNull() ?: 0L
            val source = hash["source"]?.ifBlank { null } ?: "KIS"

            // 과거 분이면 그 bar 의 실제 ts 로 발행(보통 closingTs 와 동일).
            val ts = barTs

            // idempotent UPSERT — 신규 INSERT 또는 KIS-RANGE-GAPFILL 교정(rows==1) 일 때만 발행.
            // @Modifying native query 는 트랜잭션 필수 (TransactionRequiredException 방지 —
            // 본 클래스는 @Scheduled 진입이라 BoundaryAggregator 와 달리 @Transactional 프록시가 없음.
            // private 자기호출이므로 메서드 어노테이션 대신 TransactionTemplate 로 UPSERT 만 정밀 래핑).
            val affected =
                transactionTemplate.execute {
                    candleRepository.insertOrUpdateIfGapfill(
                        code = code,
                        interval = "1m",
                        ts = ts,
                        openRaw = openRaw,
                        highRaw = highRaw,
                        lowRaw = lowRaw,
                        closeRaw = closeRaw,
                        volume = volume,
                        source = source,
                    )
                } ?: 0

            if (affected < 1) {
                // 이미 실제 row 존재 = 같은 ts 이중 처리. 발행 skip (중복 Kafka 발행 차단).
                log.debug("1m candle 이미 처리됨 (idempotent, 발행 skip): code={} ts={}", code, ts)
                return
            }

            log.debug("1m candle DB UPSERT: code={} ts={} close={}", code, ts, closeRaw)
            publishMinuteCandle(code, ts, openRaw, highRaw, lowRaw, closeRaw, volume, source)
        } finally {
            // 스냅샷 임시 키 DEL — 다음 분봉 준비. 복원 path 에서도 임시 키는 제거.
            redisTemplate.delete(snapshotKey)
        }
    }

    /**
     * 미래 분 bar 복원 — live 키가 비어있을 때만 스냅샷 데이터를 되돌린다.
     * RENAME 이후 신규 tick 이 이미 live 키를 채웠다면(같은 분 누적) 그 데이터가 권위이므로 복원하지 않는다.
     */
    private fun restoreSnapshot(
        liveKey: String,
        snapshotKey: String,
        hash: Map<String, String>,
    ) {
        try {
            val liveExists = redisTemplate.hasKey(liveKey)
            if (liveExists == true) {
                // 신규 tick 이 같은 분을 이미 누적 중 — 복원 시 OHLC 충돌 위험. 스냅샷만 폐기.
                return
            }
            redisTemplate.opsForHash<String, String>().putAll(liveKey, hash)
            // live 키 TTL 은 WS bridge 가 다음 tick 에서 재설정(liveCandleTtl 1m=2분). 안전망 2분 부여.
            redisTemplate.expire(liveKey, java.time.Duration.ofMinutes(2))
        } catch (e: Exception) {
            log.warn("1m closing 스냅샷 복원 실패(무시): liveKey={} err={}", liveKey, e.message)
        }
    }

    /** 스냅샷 해시의 bar 시작 ts 파싱 — startTs 우선, 없으면 ts. */
    private fun parseBarTs(hash: Map<String, String>): Instant? {
        val raw = hash["startTs"] ?: hash["ts"] ?: return null
        return try {
            Instant.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun publishMinuteCandle(
        code: String,
        ts: Instant,
        openRaw: BigDecimal,
        highRaw: BigDecimal,
        lowRaw: BigDecimal,
        closeRaw: BigDecimal,
        volume: Long,
        source: String,
    ) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to code,
                        "interval" to "1m",
                        "ts" to ts.toString(),
                        "open" to openRaw.toPlainString(),
                        "high" to highRaw.toPlainString(),
                        "low" to lowRaw.toPlainString(),
                        "close" to closeRaw.toPlainString(),
                        "openRaw" to openRaw.toPlainString(),
                        "highRaw" to highRaw.toPlainString(),
                        "lowRaw" to lowRaw.toPlainString(),
                        "closeRaw" to closeRaw.toPlainString(),
                        "volume" to volume,
                        "source" to source,
                    ),
                )
            kafkaTemplate.send(KafkaTopics.Candle.MINUTE_CLOSED, code, payload)
            log.debug("1m candle Kafka publish: code={} ts={}", code, ts)
        } catch (e: Exception) {
            log.error("1m candle Kafka publish 실패: code={} error={}", code, e.message)
        }
    }
}
