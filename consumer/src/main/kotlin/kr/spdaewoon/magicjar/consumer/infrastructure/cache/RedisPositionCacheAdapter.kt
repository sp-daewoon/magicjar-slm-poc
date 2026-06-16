package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.position.port.KisHolding
import kr.spdaewoon.magicjar.domain.position.port.PositionCachePort
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

/**
 * Redis 포지션 캐시 어댑터 (ADR 0042 Bootstrap STAGE 0-3).
 * - key: `position:current:{userId}` TTL 24h
 * - put: KisHolding list → JSON 직렬화 → Redis SET
 * - getAll: Redis GET → JSON 역직렬화 → Position list
 * - applyExecution·getLastUpdatedAt: T-W2 시점 구현 예정 (TODO 스텁)
 */
@Component
class RedisPositionCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : PositionCachePort {
    private val log = LoggerFactory.getLogger(RedisPositionCacheAdapter::class.java)

    companion object {
        private val DEFAULT_TTL = Duration.ofHours(24)
    }

    /**
     * 보유 포지션 list를 Redis에 저장 (TTL 24h — ADR 0042 §2-3).
     */
    fun putHoldings(
        userId: String,
        holdings: List<KisHolding>,
        ttl: Duration = DEFAULT_TTL,
    ) {
        val key = RedisKeyRegistry.Account.currentPosition(userId)
        val json = objectMapper.writeValueAsString(holdings)
        redisTemplate.opsForValue().set(key, json, ttl)
        log.debug("Redis 포지션 캐시 저장: user={} holdings={}건 ttl={}", maskUserId(userId), holdings.size, ttl)
    }

    // ── PositionCachePort 구현 ─────────────────────────────────────────────

    override fun getAll(userId: String): List<Position> {
        val key = RedisKeyRegistry.Account.currentPosition(userId)
        val json = redisTemplate.opsForValue().get(key) ?: return emptyList()
        return try {
            val holdings: List<CachedHolding> = objectMapper.readValue(json)
            holdings.map { h ->
                Position(
                    userId = userId,
                    code = h.code,
                    quantity = h.quantity,
                    avgPrice = h.avgPrice,
                    updatedAt =
                        kotlinx.datetime.Clock.System
                            .now(),
                )
            }
        } catch (e: Exception) {
            log.warn("Redis 포지션 캐시 역직렬화 실패 — cache miss 처리: {}", e.message)
            emptyList()
        }
    }

    override fun put(position: Position) {
        // 단건 put은 putHoldings로 위임 (T-W2에서 체결 통보 시 refresh)
        val key = RedisKeyRegistry.Account.currentPosition(position.userId)
        val current = getAll(position.userId).toMutableList()
        val idx = current.indexOfFirst { it.code == position.code }
        if (idx >= 0) current[idx] = position else current.add(position)
        val holdings = current.map { CachedHolding(it.code, it.quantity, it.avgPrice) }
        val json = objectMapper.writeValueAsString(holdings)
        redisTemplate.opsForValue().set(key, json, DEFAULT_TTL)
    }

    override fun putAll(
        userId: String,
        positions: List<Position>,
    ) {
        val holdings = positions.map { CachedHolding(it.code, it.quantity, it.avgPrice) }
        putHoldings(userId, holdings.map { KisHolding(it.code, it.quantity, it.avgPrice) })
    }

    override fun evict(
        userId: String,
        code: String,
    ) {
        // T-W2 시점 구현 — 청산 종목 제거
        TODO("T-W2 구현 예정 — evict(userId=$userId, code=$code)")
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /** JSON 직렬화용 내부 DTO */
    data class CachedHolding(
        val code: String,
        val quantity: Int,
        val avgPrice: BigDecimal,
    )
}
