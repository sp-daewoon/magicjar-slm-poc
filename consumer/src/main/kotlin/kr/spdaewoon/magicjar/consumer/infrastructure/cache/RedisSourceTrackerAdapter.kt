package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import kr.spdaewoon.magicjar.domain.market.port.SourceTracker
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis 종목별 시세 수집 source 추적 어댑터 (ADR 0048 §3-5).
 * - key: watchlist:source:{userId}  TTL 24h (매 변경 시 갱신)
 * - field: code (종목코드)
 * - value: "live" | "polling" | "idle"
 * - opsForHash 사용 (Redis HASH 구조)
 */
@Component
class RedisSourceTrackerAdapter(
    private val redisTemplate: StringRedisTemplate,
) : SourceTracker {
    private val log = LoggerFactory.getLogger(RedisSourceTrackerAdapter::class.java)

    companion object {
        private val SOURCE_TTL = Duration.ofHours(24)
    }

    override fun set(
        userId: String,
        code: String,
        source: String,
    ) {
        val key = RedisKeyRegistry.Ops.watchlistSource(userId)
        redisTemplate.opsForHash<String, String>().put(key, code, source)
        redisTemplate.expire(key, SOURCE_TTL)
        log.debug("source tracker set: userId={} code={} source={}", maskUserId(userId), code, source)
    }

    override fun get(
        userId: String,
        code: String,
    ): String? = redisTemplate.opsForHash<String, String>().get(RedisKeyRegistry.Ops.watchlistSource(userId), code)

    override fun remove(
        userId: String,
        code: String,
    ) {
        redisTemplate.opsForHash<String, String>().delete(RedisKeyRegistry.Ops.watchlistSource(userId), code)
        log.debug("source tracker remove: userId={} code={}", maskUserId(userId), code)
    }

    override fun listForUser(userId: String): Map<String, String> =
        redisTemplate
            .opsForHash<String, String>()
            .entries(RedisKeyRegistry.Ops.watchlistSource(userId))

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
