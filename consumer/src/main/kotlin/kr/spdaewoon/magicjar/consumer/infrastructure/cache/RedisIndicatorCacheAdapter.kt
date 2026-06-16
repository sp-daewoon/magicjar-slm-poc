package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

/**
 * Redis 지표 캐시 어댑터 (ADR 0021 §2-5).
 *
 * key: `indicator:cache:{code}:{interval}:{name}:{paramsHash}` (paramsHash = SHA-256 16자리 hex prefix)
 * value: JSON IndicatorResult (BigDecimal → String, Instant → ISO String 직렬화)
 * TTL:
 *   - 분봉 (1m·3m·5m·15m·30m·60m·90m·1h·4h·12h): 300s (5분)
 *   - 일봉 이상 (1d·1wk·1mo·1y): 86400s (1일)
 *
 * spec §6-4 LOCK 정합.
 */
@Component
class RedisIndicatorCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RedisIndicatorCacheAdapter::class.java)

    companion object {
        private val INTRADAY_INTERVALS = setOf("1m", "2m", "3m", "5m", "15m", "30m", "60m", "90m", "1h", "4h", "12h")
        private val INTRADAY_TTL = Duration.ofSeconds(300)
        private val DAILY_TTL = Duration.ofSeconds(86400)
    }

    fun cacheKey(
        code: String,
        interval: String,
        spec: IndicatorSpec,
    ): String = RedisKeyRegistry.Indicator.result(code, interval, spec.name, spec.paramsHash())

    fun get(
        code: String,
        interval: String,
        spec: IndicatorSpec,
    ): IndicatorResult? {
        val key = cacheKey(code, interval, spec)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return try {
            decode(json)
        } catch (e: Exception) {
            log.warn("Indicator cache 역직렬화 실패: key={} err={}", key, e.message)
            null
        }
    }

    fun put(
        code: String,
        interval: String,
        result: IndicatorResult,
    ) {
        val key = cacheKey(code, interval, result.spec)
        val json = encode(result)
        val ttl = if (interval in INTRADAY_INTERVALS) INTRADAY_TTL else DAILY_TTL
        redisTemplate.opsForValue().set(key, json, ttl)
    }

    fun evict(
        code: String,
        interval: String,
        spec: IndicatorSpec,
    ) {
        redisTemplate.delete(cacheKey(code, interval, spec))
    }

    private fun encode(result: IndicatorResult): String {
        // BigDecimal → String (toPlainString) · Instant → ISO String 으로 평탄화
        val flatSpec =
            mapOf(
                "name" to result.spec.name,
                "params" to result.spec.params.mapValues { stringify(it.value) },
            )
        val flatSeries =
            result.series.map { p ->
                mapOf(
                    "time" to p.time.toString(),
                    "values" to p.values.mapValues { it.value.toPlainString() },
                )
            }
        val flat =
            mapOf(
                "spec" to flatSpec,
                "series" to flatSeries,
                "source" to result.source,
            )
        return objectMapper.writeValueAsString(flat)
    }

    private fun decode(json: String): IndicatorResult {
        @Suppress("UNCHECKED_CAST")
        val map = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val specMap = map["spec"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val params = (specMap["params"] as Map<String, Any?>).mapValues { it.value?.toString() ?: "" }
        val spec = IndicatorSpec(specMap["name"] as String, params)

        @Suppress("UNCHECKED_CAST")
        val seriesRaw = map["series"] as List<Map<String, Any?>>
        val series =
            seriesRaw.map { p ->
                @Suppress("UNCHECKED_CAST")
                val values = (p["values"] as Map<String, Any?>).mapValues { BigDecimal((it.value ?: "0").toString()) }
                IndicatorPoint(time = Instant.parse(p["time"] as String), values = values)
            }
        return IndicatorResult(spec, series, map["source"] as String)
    }

    private fun stringify(value: Any?): String =
        when (value) {
            is BigDecimal -> value.toPlainString()
            null -> ""
            else -> value.toString()
        }
}
