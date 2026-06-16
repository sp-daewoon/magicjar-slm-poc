package kr.spdaewoon.magicjar.api.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerCachePort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * ScreenerCachePort Redis 구현 (ADR 0071 §5 + spec §2-3·§5).
 *
 * ### Redis 키 매트릭스
 * - `screener:cache:{dslHash}:{universeFilterHash}` TTL 30m — 결과 캐시
 * - `screener:universe:{filterHash}` TTL 24h — universe instrument list 캐시
 * - `screener:lock:{userId}:{strategyId}` TTL 1m — 분산락 SETNX
 *
 * Jackson JSON serialize/deserialize. ScreenerRunResult는 data class이므로 무설정 직렬화 가능.
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * LLM provider import 0 — grep 검증 의무.
 *
 * ### 헌법 §6 정합
 * abstract fun 단독 구현 — body fun 0건.
 */
@Component
class RedisScreenerCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : ScreenerCachePort {
    private val log = LoggerFactory.getLogger(RedisScreenerCacheAdapter::class.java)

    companion object {
        private const val LOCK_VALUE = "1"
    }

    /**
     * 결과 캐시 조회.
     * @return 캐시 HIT 시 ScreenerRunResult (cacheHit=true 세팅됨), MISS 시 null.
     */
    override fun get(cacheKey: String): ScreenerRunResult? {
        val json =
            redisTemplate.opsForValue().get(cacheKey) ?: run {
                log.debug("screener cache MISS: key={}", cacheKey)
                return null
            }
        return runCatching {
            val result = objectMapper.readValue<ScreenerRunResult>(json)
            result.copy(cacheHit = true, cacheKey = cacheKey)
        }.getOrElse { ex ->
            log.warn("screener cache 역직렬화 실패: key={} err={}", cacheKey, ex.message)
            null
        }.also {
            if (it != null) log.debug("screener cache HIT: key={}", cacheKey)
        }
    }

    /**
     * 결과 캐시 저장.
     * TTL default 30분 (ADR 0071 §5).
     */
    override fun put(
        cacheKey: String,
        result: ScreenerRunResult,
        ttl: Duration,
    ) {
        val json = objectMapper.writeValueAsString(result)
        redisTemplate.opsForValue().set(cacheKey, json, ttl)
        log.debug("screener cache PUT: key={} matchedCount={} ttl={}", cacheKey, result.matchedCount, ttl)
    }

    /**
     * 분산락 SETNX — 동시 동일 요청 1회만 실행 보장 (ADR 0071 §5).
     *
     * @return true=락 획득 성공 / false=이미 평가 중 (동시 호출)
     */
    override fun tryLock(
        userId: Long,
        strategyId: Long,
        ttl: Duration,
    ): Boolean {
        val key = buildLockKey(userId, strategyId)
        val acquired = redisTemplate.opsForValue().setIfAbsent(key, LOCK_VALUE, ttl) ?: false
        log.debug("screener lock tryAcquire: key={} acquired={}", key, acquired)
        return acquired
    }

    /**
     * 분산락 해제 (finally 블록에서 반드시 호출).
     */
    override fun releaseLock(
        userId: Long,
        strategyId: Long,
    ) {
        val key = buildLockKey(userId, strategyId)
        redisTemplate.delete(key)
        log.debug("screener lock released: key={}", key)
    }

    // ── private helper ───────────────────────────────────────────────────

    private fun buildLockKey(
        userId: Long,
        strategyId: Long,
    ): String = RedisKeyRegistry.Screener.lock(userId.toString(), strategyId)
}
