package kr.spdaewoon.magicjar.consumer.reconciliation

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Non-watchlist realtime demand snapshot for boot reconciliation.
 *
 * DETAIL/STRATEGY demand itself remains owned by RealtimeSubscriptionRouter. This cache only preserves
 * recent symbols long enough for CandleBootstrapRunner to repair candles after restart.
 */
@Component
class ManagedCandleUniverseCache(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(ManagedCandleUniverseCache::class.java)

    companion object {
        private val DETAIL_TTL = Duration.ofHours(24)
        private val STRATEGY_TTL = Duration.ofMinutes(30)
        private val CODE_REGEX = Regex("^\\d{6}$")
    }

    fun recordDetail(code: String) {
        record(RedisKeyRegistry.Market.candleUniverseDetailRecent(), code, DETAIL_TTL)
    }

    fun recordStrategyCandidate(
        code: String,
        ttlSeconds: Long? = null,
    ) {
        val ttl =
            ttlSeconds
                ?.coerceAtLeast(1L)
                ?.let(Duration::ofSeconds)
                ?: STRATEGY_TTL
        record(RedisKeyRegistry.Market.candleUniverseStrategyCandidates(), code, ttl)
    }

    fun recentDetailCodes(): Set<String> = readActiveCodes(RedisKeyRegistry.Market.candleUniverseDetailRecent())

    fun strategyCandidateCodes(): Set<String> = readActiveCodes(RedisKeyRegistry.Market.candleUniverseStrategyCandidates())

    private fun record(
        key: String,
        code: String,
        ttl: Duration,
    ) {
        if (!CODE_REGEX.matches(code)) return
        try {
            val expiresAt = System.currentTimeMillis() + ttl.toMillis()
            redisTemplate.opsForZSet().add(key, code, expiresAt.toDouble())
            redisTemplate.expire(key, ttl.plusMinutes(5))
        } catch (e: Exception) {
            log.warn("managed candle universe cache 기록 실패: key={} code={} err={}", key, code, e.message)
        }
    }

    private fun readActiveCodes(key: String): Set<String> =
        try {
            val now = System.currentTimeMillis().toDouble()
            redisTemplate.opsForZSet().removeRangeByScore(key, 0.0, now - 1)
            redisTemplate
                .opsForZSet()
                .rangeByScore(key, now, Double.MAX_VALUE)
                .orEmpty()
                .filterTo(linkedSetOf()) { CODE_REGEX.matches(it) }
        } catch (e: Exception) {
            log.warn("managed candle universe cache 조회 실패: key={} err={}", key, e.message)
            emptySet()
        }
}
