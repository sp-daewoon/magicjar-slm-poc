package kr.spdaewoon.magicjar.api.cache

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 실시간 시그널 평가용 활성 전략 Redis cache (ADR 0066 §2-5 D-1).
 *
 * ### Redis key 패턴
 * `strategy:active:by_code:{code}:{interval}`
 *
 * ### value 형식
 * JSON 배열 — [strategyId, strategyId, ...]
 *
 * ### TTL
 * 5분 (사용자 strategy 갱신/토글 시 [invalidate] 또는 [invalidateAll] 호출로 즉시 무효화)
 *
 * ### 사용 흐름
 * [StrategyLookupJpaAdapter.findActiveByCodeAndInterval] → [getActive] 1차 조회
 * → cache miss 시 DB 조회 → [putActive]
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class RedisStrategyCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RedisStrategyCache::class.java)

    companion object {
        private val TTL = Duration.ofMinutes(5)
        private val ID_LIST_TYPE = object : TypeReference<List<Long>>() {}
    }

    /**
     * 활성 전략 ID 목록 캐시 조회.
     *
     * @return cache hit 시 [List<Long>], miss 시 null
     */
    fun getActive(
        code: String,
        interval: String,
    ): List<Long>? {
        val key = buildKey(code, interval)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return runCatching {
            objectMapper.readValue(json, ID_LIST_TYPE)
        }.getOrElse {
            log.warn("Redis cache 역직렬화 실패: key={} err={}", key, it.message)
            null
        }
    }

    /**
     * 활성 전략 ID 목록을 캐시에 저장 (TTL 5분).
     */
    fun putActive(
        code: String,
        interval: String,
        ids: List<Long>,
    ) {
        val key = buildKey(code, interval)
        val json = objectMapper.writeValueAsString(ids)
        redisTemplate.opsForValue().set(key, json, TTL)
        log.debug("cache put: {} → {} ids", key, ids.size)
    }

    /**
     * 특정 code·interval 캐시 무효화.
     * 전략 토글(ACTIVE↔PAUSED/DISABLED) 시 호출.
     */
    fun invalidate(
        code: String,
        interval: String,
    ) {
        val key = buildKey(code, interval)
        redisTemplate.delete(key)
        log.debug("cache invalidate: {}", key)
    }

    /**
     * 전략 상태 전환 시 일괄 무효화 (keys scan).
     * 전략 생성·삭제·전체 상태 변경 시 호출.
     *
     * 주의: keys는 keyspace 크기에 따라 부하 발생 가능 — 사용자 액션(낮은 빈도)에서만 호출.
     */
    fun invalidateAll() {
        val pattern = RedisKeyRegistry.Strategy.activeByCodePattern()
        val keys = redisTemplate.keys(pattern)
        val count = if (keys.isNullOrEmpty()) 0L else redisTemplate.delete(keys).toLong()
        log.info("cache invalidateAll: {}개 키 삭제", count)
    }

    // --- private helper ---

    private fun buildKey(
        code: String,
        interval: String,
    ): String = RedisKeyRegistry.Strategy.activeByCode(code, interval)
}
