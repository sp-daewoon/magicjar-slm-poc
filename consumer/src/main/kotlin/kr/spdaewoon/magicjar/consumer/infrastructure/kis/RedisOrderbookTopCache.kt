package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * `market:orderbook:top:code:{code}` 1호가 Redis 캐시 read — ADR 0114 §10-3 R2-C1 진입 호가 **1차 source**.
 *
 * 쓰기(write-through)는 `KisWebSocketBridge.writeOrderbookTopToRedis`(H0UNASP0 수신 경로)가 담당하고,
 * 본 클래스는 **읽기만** 한다. 호가 WS 미구독 종목은 키가 없어 miss가 정상이다
 * (합성 어댑터 [CompositeEntryQuoteAdapter]가 REST fallback으로 넘어감).
 *
 * 캐시 payload(JSON): `ask1`·`bid1`·`askQty1`·`bidQty1`·`ts`. stale 판정은 EntryPricePolicy가 quoteAsOf(=ts)로 수행.
 */
@Component
class RedisOrderbookTopCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RedisOrderbookTopCache::class.java)

    /**
     * `market:orderbook:top:code:{code}` 조회. 캐시 hit이고 ask1/bid1 유효(>0)면 [EntryQuote], 그 외 null(miss/파싱 실패).
     */
    fun read(code: String): EntryQuote? {
        val raw =
            try {
                redisTemplate.opsForValue().get(RedisKeyRegistry.Market.orderbookTop(code))
            } catch (e: Exception) {
                log.warn("market:orderbook:top Redis 조회 실패: code={} error={}", code, e.message)
                return null
            } ?: return null

        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(raw, Map::class.java) as Map<String, Any?>
            val ask1 = (map["ask1"] as? String)?.ifBlank { null }?.toBigDecimalOrNull()
            val bid1 = (map["bid1"] as? String)?.ifBlank { null }?.toBigDecimalOrNull()
            if (ask1 == null || ask1 <= BigDecimal.ZERO || bid1 == null || bid1 <= BigDecimal.ZERO) {
                return null
            }
            val askQty1 = (map["askQty1"] as? String)?.ifBlank { null }?.toLongOrNull()
            val ts = (map["ts"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            EntryQuote(ask1 = ask1, bid1 = bid1, askQty1 = askQty1, asOf = ts)
        } catch (e: Exception) {
            log.warn("market:orderbook:top 파싱 실패: code={} raw={} error={}", code, raw.take(120), e.message)
            null
        }
    }
}
