package kr.spdaewoon.magicjar.pattern.cache

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration

/**
 * Redis 패턴 캐시 어댑터 — consumer 모듈 (spec §6-5 LOCK — T-IND RedisIndicatorCacheAdapter 정합).
 *
 * key: `pattern:cache:{code}:{interval}:{patternsHash}` (patternsHash = SHA-256 16자리 hex prefix)
 * value: JSON 직렬화 (List 형태 직렬화)
 * TTL:
 *   - 분봉 (1m·2m·5m·15m·30m·90m·1h): 300s (5분)
 *   - 일봉 이상 (1d·1wk·1mo·1y): 86400s (1일)
 *
 * api 모듈 RedisPatternCacheAdapter와 동일 키·TTL 정책 — api·consumer 캐시 공유.
 * ADR 0051 — 신규 JPA Repository 0건 → default fun 함정 발동 0.
 * 룰 5 — LLM provider import 0건.
 */
@Component
class RedisPatternCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RedisPatternCacheAdapter::class.java)

    companion object {
        private val INTRADAY_INTERVALS = setOf("1m", "2m", "5m", "15m", "30m", "90m", "1h")
        private val INTRADAY_TTL = Duration.ofSeconds(300)
        private val DAILY_TTL = Duration.ofSeconds(86400)

        /**
         * patternsHash 계산 (spec §6-5 LOCK).
         * patterns sorted + from + to → SHA-256 → 16자리 hex prefix.
         */
        fun patternsHash(
            patterns: Set<String>,
            from: String?,
            to: String?,
        ): String {
            val sorted = patterns.sorted().joinToString(",")
            val key = "$sorted|$from|$to"
            return MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
        }
    }

    fun cacheKey(
        code: String,
        interval: String,
        patterns: Set<String>,
        from: String?,
        to: String?,
    ): String = RedisKeyRegistry.Pattern.result(code, interval, patternsHash(patterns, from, to))

    /** JSON 값 조회 (miss 시 null) */
    fun get(
        code: String,
        interval: String,
        patterns: Set<String>,
        from: String?,
        to: String?,
    ): String? {
        val key = cacheKey(code, interval, patterns, from, to)
        return try {
            redisTemplate.opsForValue().get(key).also { v ->
                if (v != null) {
                    log.debug("pattern cache HIT: key={}", key)
                } else {
                    log.debug("pattern cache MISS: key={}", key)
                }
            }
        } catch (e: Exception) {
            log.debug("pattern cache get 실패: key={} err={}", key, e.message)
            null
        }
    }

    /** JSON 값 저장 */
    fun put(
        code: String,
        interval: String,
        patterns: Set<String>,
        from: String?,
        to: String?,
        json: String,
    ) {
        val key = cacheKey(code, interval, patterns, from, to)
        val ttl = if (interval in INTRADAY_INTERVALS) INTRADAY_TTL else DAILY_TTL
        try {
            redisTemplate.opsForValue().set(key, json, ttl)
            log.debug("pattern cache SET: key={} ttl={}s", key, ttl.seconds)
        } catch (e: Exception) {
            log.debug("pattern cache set 실패: key={} err={}", key, e.message)
        }
    }

    /** 직렬화 헬퍼 */
    fun encode(value: Any): String = objectMapper.writeValueAsString(value)
}
