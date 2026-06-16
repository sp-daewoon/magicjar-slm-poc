package kr.spdaewoon.magicjar.api.infrastructure.naver

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Naver Open API 분당 호출 제한 — Redis ZSET 슬라이딩 윈도 (T-NEWS Phase 2 — ADR 0075 §4-2).
 *
 * Naver 한도: 25,000건/일 → 분당 17건 (25,000 ÷ 1,440 ≈ 17.3) hard limit (ADR 0075 §4-2).
 * Resilience4j RateLimiter 폐기 — 다중 컨테이너 + 글로벌 한도 보호 → Redis 분산 sliding window.
 *
 * Redis ZSET 키: news:ratelimit:naver (ADR 0075 §4-1)
 * 알고리즘:
 *   1. 60초 이전 score(epoch ms) 제거 — 만료 slot 정리
 *   2. 현재 윈도 카운트 확인 — limitPerMinute 도달 시 false 반환
 *   3. 현재 timestamp + UUID 멤버로 ZADD — slot 점유
 *   4. EXPIRE 120s — 윈도 자동 정리
 *
 * 초과 시 graceful — false 반환 + log WARN + 다음 cron 대기 (WatchlistNewsScheduler 정합).
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Component
class NaverNewsRateLimiter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(NaverNewsRateLimiter::class.java)

    companion object {
        /** Redis ZSET 키 (ADR 0075 §4-1) */
        val RATE_LIMIT_KEY: String = RedisKeyRegistry.News.naverRateLimit()

        /** Naver 한도: 분당 17건 (25K/일 ÷ 1440 — 0건 마진 hard limit) */
        const val LIMIT_PER_MINUTE = 17

        /** 슬라이딩 윈도 60초 */
        private const val WINDOW_MILLIS = 60_000L

        /** ZSET key TTL — 윈도의 2배 (자동 정리) */
        private val KEY_TTL = Duration.ofSeconds(120)
    }

    /**
     * Naver API 호출 슬롯 획득.
     *
     * @return true = 슬롯 획득 성공 (호출 가능), false = 분당 한도 초과 (호출 불가)
     */
    fun acquireSlot(): Boolean = acquireSlotWithLimit(LIMIT_PER_MINUTE)

    /**
     * 슬롯 획득 (테스트 주입 가능 — limit 파라미터).
     *
     * ADR 0075 §4-2 pseudo 구현:
     * ```
     * redis.zremrangeByScore(key, "-inf", windowStart)
     * count = redis.zcard(key)
     * if (count >= limit) return false
     * redis.zadd(key, now, "$now:${UUID}")
     * redis.expire(key, 120)
     * return true
     * ```
     */
    fun acquireSlotWithLimit(limit: Int): Boolean {
        val now = Instant.now().toEpochMilli()
        val windowStart = now - WINDOW_MILLIS

        val ops = redisTemplate.opsForZSet()

        // 60초 이전 만료 슬롯 제거
        ops.removeRangeByScore(RATE_LIMIT_KEY, Double.NEGATIVE_INFINITY, windowStart.toDouble())

        // 현재 윈도 카운트 확인
        val currentCount = ops.zCard(RATE_LIMIT_KEY) ?: 0L
        if (currentCount >= limit) {
            log.warn(
                "[NaverRateLimit] 분당 한도 초과 — count={} limit={} key={}",
                currentCount,
                limit,
                RATE_LIMIT_KEY,
            )
            return false
        }

        // 슬롯 점유 (현재 timestamp + UUID 멤버 — 동시 중복 방지)
        val member = "$now:${UUID.randomUUID()}"
        ops.add(RATE_LIMIT_KEY, member, now.toDouble())
        redisTemplate.expire(RATE_LIMIT_KEY, KEY_TTL)

        log.debug("[NaverRateLimit] 슬롯 획득 — count={} limit={}", currentCount + 1, limit)
        return true
    }

    /**
     * 현재 윈도 카운트 조회 (모니터링·테스트 용).
     */
    fun currentWindowCount(): Long {
        val now = Instant.now().toEpochMilli()
        val windowStart = now - WINDOW_MILLIS
        val ops = redisTemplate.opsForZSet()
        ops.removeRangeByScore(RATE_LIMIT_KEY, Double.NEGATIVE_INFINITY, windowStart.toDouble())
        return ops.zCard(RATE_LIMIT_KEY) ?: 0L
    }
}
