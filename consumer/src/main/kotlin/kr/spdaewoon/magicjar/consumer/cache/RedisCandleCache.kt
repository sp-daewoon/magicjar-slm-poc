package kr.spdaewoon.magicjar.consumer.cache

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import kotlinx.datetime.Instant as KtInstant

/**
 * 장중 진행 중 캔들 Redis hash 캐시 어댑터 — boundary 캐시 (ADR 0064 A-1 채택).
 *
 * ADR 0029 §2-1 매트릭스 LOCK (3m·5m·15m·30m·90m·1h·4h·12h 별도 키):
 *   candle:current:{code}:3m   → 당일 3m bar  (3분 boundary 마감 시 갱신)
 *   candle:current:{code}:5m   → 당일 5m bar  (5분 boundary 마감 시 갱신)
 *   candle:current:{code}:15m  → 당일 15m bar (15분 boundary 마감 시 갱신)
 *   candle:current:{code}:30m  → 당일 30m bar (30분 boundary 마감 시 갱신)
 *   candle:current:{code}:90m  → 당일 90m bar (90분 boundary 마감 시 갱신)
 *   candle:current:{code}:1h   → 당일 1h bar  (60분 boundary 마감 시 갱신)
 *   candle:current:{code}:4h   → 당일 4h bar  (4시간 boundary 마감 시 갱신)
 *   candle:current:{code}:12h  → 당일 12h bar (12시간 boundary 마감 시 갱신)
 *
 * Hash fields: ts(epochSecond), open, high, low, close, volume, source
 * TTL: 25h (장 마감(20:00) 후 다음 영업일 갱신 보장, DailyCloseFinalizer evict 전 유지)
 *
 * 지원 interval: THREE_MIN·FIVE_MIN·FIFTEEN_MIN·THIRTY_MIN·NINETY_MIN·ONE_HOUR·FOUR_HOUR·TWELVE_HOUR
 * 1m·1d 는 KisWebSocketBridge·DailyCloseFinalizer 각각 처리 — 본 클래스 범위 외.
 *
 * 헌법 §6 JPA default fun 함정 해당 없음 (Redis 어댑터 — JPA interface 아님).
 */
@Component
class RedisCandleCache(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(RedisCandleCache::class.java)

    companion object {
        private val TTL = Duration.ofHours(25)

        private val SUPPORTED_INTERVALS =
            setOf(
                CandleInterval.THREE_MIN,
                CandleInterval.FIVE_MIN,
                CandleInterval.FIFTEEN_MIN,
                CandleInterval.THIRTY_MIN,
                CandleInterval.NINETY_MIN,
                CandleInterval.ONE_HOUR,
                CandleInterval.FOUR_HOUR,
                CandleInterval.TWELVE_HOUR,
            )
    }

    /**
     * boundary 마감 캔들을 Redis hash로 저장.
     *
     * key: candle:current:{code}:{interval.code}
     * fields: ts, open, high, low, close, volume, source
     *
     * @param code 종목코드 (KIS 6자리)
     * @param interval target interval (THREE_MIN·FIVE_MIN·FIFTEEN_MIN·THIRTY_MIN·NINETY_MIN·ONE_HOUR·FOUR_HOUR·TWELVE_HOUR)
     * @param candle 합산 완결된 캔들 (boundary 마감 bar)
     */
    fun putBoundaryCandle(
        code: String,
        interval: CandleInterval,
        candle: Candle,
    ) {
        require(interval in SUPPORTED_INTERVALS) {
            "RedisCandleCache: 지원하지 않는 interval=$interval (지원: 3m·5m·15m·30m·90m·1h·4h·12h)"
        }
        val key = buildKey(code, interval)
        try {
            val fields =
                mapOf(
                    "ts" to candle.ts.epochSeconds.toString(),
                    "open" to candle.openRaw.toPlainString(),
                    "high" to candle.highRaw.toPlainString(),
                    "low" to candle.lowRaw.toPlainString(),
                    "close" to candle.closeRaw.toPlainString(),
                    "volume" to candle.volume.toString(),
                    "source" to candle.source,
                )
            redisTemplate.opsForHash<String, String>().putAll(key, fields)
            redisTemplate.expire(key, TTL)
            log.debug(
                "RedisCandleCache.put: key={} ts={} close={}",
                key,
                candle.ts,
                candle.closeRaw,
            )
        } catch (e: Exception) {
            log.error("RedisCandleCache.put 실패: key={} err={}", key, e.message)
        }
    }

    /**
     * boundary 마감 캔들 조회.
     *
     * @return Candle? — null if not found or parse error
     */
    fun getBoundaryCandle(
        code: String,
        interval: CandleInterval,
    ): Candle? {
        require(interval in SUPPORTED_INTERVALS) {
            "RedisCandleCache: 지원하지 않는 interval=$interval (지원: 3m·5m·15m·30m·90m·1h·4h·12h)"
        }
        val key = buildKey(code, interval)
        return try {
            val hash = redisTemplate.opsForHash<String, String>().entries(key)
            if (hash.isEmpty()) {
                log.debug("RedisCandleCache.get: key={} 데이터 없음", key)
                return null
            }
            parseHash(hash, code, interval)
        } catch (e: Exception) {
            log.error("RedisCandleCache.get 실패: key={} err={}", key, e.message)
            null
        }
    }

    /**
     * boundary 마감 캔들 키 삭제.
     * boundary 마감 INSERT 후 evict (ADR 0029 §2-1 evict 정책 정합).
     * DailyCloseFinalizer 20:00 flush 시에도 호출됨 (flushAggregations 후 evict 가능).
     */
    fun evictBoundaryCandle(
        code: String,
        interval: CandleInterval,
    ) {
        val key = buildKey(code, interval)
        try {
            redisTemplate.delete(key)
            log.debug("RedisCandleCache.evict: key={}", key)
        } catch (e: Exception) {
            log.error("RedisCandleCache.evict 실패: key={} err={}", key, e.message)
        }
    }

    /** candle:current:{code}:{interval.code} */
    private fun buildKey(
        code: String,
        interval: CandleInterval,
    ): String = RedisKeyRegistry.Market.candleCurrent(code, interval.code)

    /** Redis hash → Candle 도메인 변환. */
    private fun parseHash(
        hash: Map<String, String>,
        code: String,
        interval: CandleInterval,
    ): Candle? =
        try {
            val ts = hash["ts"]?.toLongOrNull()?.let { KtInstant.fromEpochSeconds(it) } ?: return null
            val open = hash["open"]?.toBigDecimalOrNull() ?: return null
            val high = hash["high"]?.toBigDecimalOrNull() ?: return null
            val low = hash["low"]?.toBigDecimalOrNull() ?: return null
            val close = hash["close"]?.toBigDecimalOrNull() ?: return null
            val volume = hash["volume"]?.toLongOrNull() ?: 0L
            val source = hash["source"] ?: "KIS"

            Candle(
                id = null,
                code = code,
                interval = interval,
                ts = ts,
                openRaw = open,
                highRaw = high,
                lowRaw = low,
                closeRaw = close,
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = volume,
                source = source,
            )
        } catch (e: Exception) {
            log.error("RedisCandleCache.parseHash 실패: code={} interval={} err={}", code, interval, e.message)
            null
        }
}
