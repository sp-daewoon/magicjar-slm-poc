package kr.spdaewoon.magicjar.api.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate

/**
 * T-PERF Redis 캐시 어댑터 — ADR 0072 §5 Redis 키 매트릭스 정합.
 *
 * 캐시 키 패턴:
 * - `perf:summary:{userId}:{period}:{periodStart}:{periodEnd}` — Performance Summary TTL 1h
 * - `perf:equity:{userId}:{from}:{to}` — Equity Curve TTL 1h
 *
 * invalidation: pnl_daily INSERT 시점에 userId 전체 perf:* 패턴 삭제.
 * userId: String — user_account.user_id VARCHAR(40) 정합 (V1 baseline).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Component
class RedisPerformanceCacheAdapter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RedisPerformanceCacheAdapter::class.java)
    private val ttl = Duration.ofHours(1)

    // ─── Summary 캐시 ───────────────────────────────────────────────────

    fun putSummary(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        value: Any,
    ) {
        val key = summaryKey(userId, period, periodStart, periodEnd)
        val json = objectMapper.writeValueAsString(value)
        redis.opsForValue().set(key, json, ttl)
        log.debug("perf:summary 캐시 SET key={} TTL=1h", key)
    }

    fun getSummaryJson(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): String? {
        val key = summaryKey(userId, period, periodStart, periodEnd)
        return redis.opsForValue().get(key)
    }

    // ─── Equity Curve 캐시 ─────────────────────────────────────────────

    fun putEquityCurve(
        userId: String,
        from: LocalDate,
        to: LocalDate,
        value: Any,
    ) {
        val key = equityKey(userId, from, to)
        val json = objectMapper.writeValueAsString(value)
        redis.opsForValue().set(key, json, ttl)
        log.debug("perf:equity 캐시 SET key={} TTL=1h", key)
    }

    fun getEquityCurveJson(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): String? {
        val key = equityKey(userId, from, to)
        return redis.opsForValue().get(key)
    }

    // ─── Invalidation ──────────────────────────────────────────────────

    /**
     * 사용자 전체 perf:* 캐시 삭제.
     * pnl_daily INSERT 시점(DailyPerformanceJob cron)에 호출.
     * ADR 0072 §5 Redis invalidation 패턴 정합.
     */
    fun invalidateUser(userId: String) {
        deleteByPattern(RedisKeyRegistry.Performance.summaryPattern(userId))
        deleteByPattern(RedisKeyRegistry.Performance.equityPattern(userId))
    }

    // ─── private helpers ───────────────────────────────────────────────

    private fun summaryKey(
        userId: String,
        period: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): String = RedisKeyRegistry.Performance.summary(userId, period, periodStart.toString(), periodEnd.toString())

    private fun equityKey(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): String = RedisKeyRegistry.Performance.equity(userId, from.toString(), to.toString())

    private fun deleteByPattern(pattern: String) {
        runCatching {
            val keys = redis.keys(pattern)
            if (!keys.isNullOrEmpty()) {
                redis.delete(keys)
                log.debug("Redis 캐시 삭제 — pattern={} keys={}건", pattern, keys.size)
            }
        }.onFailure { log.warn("Redis 캐시 삭제 실패 — pattern={} err={}", pattern, it.message) }
    }
}
