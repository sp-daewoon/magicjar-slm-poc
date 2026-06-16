package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Redis 실시간 호가 캐시 어댑터 — consumer 모듈 (ADR 0014 T2 cache · ADR 0029).
 * - key: quote:last:{code}  TTL 5분
 * - value: JSON {"price":"...","volume":...,"ts":"...","source":"live"|"polling"}
 * - warm: KIS REST 1회 fetch (KisRestPriceAdapter) → put
 * - evict: DELETE
 */
@Component
class RedisQuoteCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : QuoteCachePort {
    private val log = LoggerFactory.getLogger(RedisQuoteCacheAdapter::class.java)

    companion object {
        private val QUOTE_TTL = Duration.ofMinutes(5)
    }

    override fun getLatestPrice(code: String): BigDecimal? {
        val json = redisTemplate.opsForValue().get(RedisKeyRegistry.Market.quoteLast(code)) ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
            (map["price"] as? String)?.let { BigDecimal(it) }
        } catch (e: Exception) {
            log.warn("Redis quote 역직렬화 실패: code={} err={}", code, e.message)
            null
        }
    }

    override fun putLatestPrice(
        code: String,
        price: BigDecimal,
    ) {
        putQuote(code, price, null, "unknown")
    }

    fun putQuote(
        code: String,
        price: BigDecimal,
        volume: Long?,
        source: String,
    ) {
        val json =
            objectMapper.writeValueAsString(
                mapOf(
                    "price" to price.toPlainString(),
                    "volume" to volume,
                    "ts" to Instant.now().toString(),
                    "source" to source,
                ),
            )
        redisTemplate.opsForValue().set(RedisKeyRegistry.Market.quoteLast(code), json, QUOTE_TTL)
    }

    fun evict(code: String) {
        redisTemplate.delete(RedisKeyRegistry.Market.quoteLast(code))
        log.debug("Redis quote evict: code={}", code)
    }
}
