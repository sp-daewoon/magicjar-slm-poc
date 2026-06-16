package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Redis ZSET sliding window rate limiter (ADR 0070 §2-4 + spec §5 LOCK).
 *
 * ### Redis 키 패턴 (spec §5)
 * `notification:ratelimit:{userId}:{topicHash}` (ZSET) TTL 60m
 *
 * ### 알고리즘
 * 1. 현재 epoch millis를 score로 ZADD
 * 2. 윈도 밖(now - windowMin*60*1000 이전) member 제거 (ZREMRANGEBYSCORE)
 * 3. 윈도 내 count 계산 (ZCARD)
 * 4. count == threshold → shouldEmit = true (윈도 내 임계 도달 시 1회만 발송)
 *    count < threshold → shouldEmit = false (아직 대기)
 *    count > threshold → shouldEmit = false (이미 해당 윈도에서 발송됨)
 *
 * P0 이벤트는 이 rate limiter를 통과하지 않음 (dispatcher에서 P0 bypass).
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
@Component
class NotificationRateLimiter(
    private val redisTemplate: StringRedisTemplate,
) : NotificationRateLimiterPort {
    private val log = LoggerFactory.getLogger(NotificationRateLimiter::class.java)

    override fun shouldEmit(
        userId: String,
        topicHash: String,
        threshold: Int,
        windowMin: Int,
    ): Boolean {
        val key = RedisKeyRegistry.Notification.rateLimit(userId, topicHash)
        val nowMs = Instant.now().toEpochMilli()
        val windowMs = windowMin * 60 * 1000L
        val windowStartMs = nowMs - windowMs

        return try {
            val zOps = redisTemplate.opsForZSet()

            // 1. 현재 이벤트 추가
            zOps.add(key, nowMs.toString(), nowMs.toDouble())

            // 2. 윈도 밖 member 제거
            zOps.removeRangeByScore(key, Double.NEGATIVE_INFINITY, windowStartMs.toDouble())

            // 3. 윈도 내 count
            val count = zOps.size(key) ?: 0L

            // 4. TTL 갱신
            redisTemplate.expire(key, Duration.ofMinutes(windowMin.toLong() + 5))

            val emit = count == threshold.toLong()
            log.debug(
                "rate limit check: key={} count={} threshold={} emit={}",
                key,
                count,
                threshold,
                emit,
            )
            emit
        } catch (e: Exception) {
            log.warn("Redis rate limit 체크 실패 — graceful allow: key={} err={}", key, e.message)
            true // fail-open: Redis 장애 시 발송 허용
        }
    }

    override fun shouldEmitOncePerWindow(
        userId: String,
        topicHash: String,
        windowMin: Int,
    ): Boolean {
        val key = RedisKeyRegistry.Notification.once(userId, topicHash)
        return try {
            val acquired =
                redisTemplate
                    .opsForValue()
                    .setIfAbsent(key, Instant.now().toEpochMilli().toString(), Duration.ofMinutes(windowMin.toLong()))
                    ?: false
            log.debug("once-per-window check: key={} windowMin={} emit={}", key, windowMin, acquired)
            acquired
        } catch (e: Exception) {
            log.warn("Redis once-per-window 체크 실패 — graceful allow: key={} err={}", key, e.message)
            true
        }
    }
}
