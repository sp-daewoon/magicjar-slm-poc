package kr.spdaewoon.magicjar.api.adapter.redis

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * KIS 관련 Redis 캐시 무효화 헬퍼 (ADR 0074 §7 정합).
 *
 * KIS 키 변경(PUT) 성공 직후 호출:
 *   1. `kis:oauth:token:user:{userId}:account:{accountType}` DEL — KIS OAuth tokenP cache
 *   2. `kis:ws:approval:user:{userId}:account:{accountType}` DEL — WS approval_key cache
 *   3. `kis:credential:changed:user:{userId}:account:{accountType}` SET TTL 5s — T-O 주문 발행 잠금
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Component
class KisCacheInvalidator(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(KisCacheInvalidator::class.java)

    companion object {
        private val KEY_CHANGED_LOCK_TTL = Duration.ofSeconds(5)
    }

    /**
     * KIS OAuth tokenP cache 무효화.
     */
    fun deleteAccessToken(
        userId: String,
        accountType: String,
    ) {
        val primaryKey = RedisKeyRegistry.Kis.token(userId, accountType)
        val primaryDeleted = redisTemplate.delete(primaryKey)
        log.info(
            "Redis KIS token DEL — userId={}**** accountType={} deleted={}",
            userId.take(4),
            accountType,
            primaryDeleted,
        )
    }

    /**
     * KIS WS approval_key cache 무효화.
     */
    fun deleteApprovalKey(
        userId: String,
        accountType: String,
    ) {
        val key = RedisKeyRegistry.Kis.approval(userId, accountType)
        val deleted = redisTemplate.delete(key)
        log.info(
            "Redis approval_key DEL — userId={}**** accountType={} deleted={}",
            userId.take(4),
            accountType,
            deleted,
        )
    }

    /**
     * kis:key-changed-lock:{userId}:{accountType} SET TTL 5초.
     * T-O OrderRouter 주문 발행 잠금 (ADR 0074 §2-2-3 5초 잠금 LOCK).
     */
    fun setKeyChangedLock(
        userId: String,
        accountType: String,
    ) {
        val key = RedisKeyRegistry.Kis.keyChangedLock(userId, accountType)
        redisTemplate.opsForValue().set(key, "1", KEY_CHANGED_LOCK_TTL)
        log.info(
            "Redis key-changed-lock SET 5s — userId={}**** accountType={}",
            userId.take(4),
            accountType,
        )
    }

    /**
     * kis:key-changed-lock:{userId}:{accountType} 존재 여부 확인.
     * T-O OrderRouter에서 주문 발행 전 호출.
     */
    fun isKeyChangedLockActive(
        userId: String,
        accountType: String,
    ): Boolean {
        val key = RedisKeyRegistry.Kis.keyChangedLock(userId, accountType)
        return redisTemplate.hasKey(key) == true
    }
}
