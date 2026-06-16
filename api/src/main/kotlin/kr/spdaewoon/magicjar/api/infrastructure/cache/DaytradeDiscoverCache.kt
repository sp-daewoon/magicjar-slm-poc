package kr.spdaewoon.magicjar.api.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverResultDto
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.CRC32

/**
 * 단타 발굴(Discovery) 결과 캐시 — perf 최적화 (28s → 캐시 HIT 시 ~ms).
 *
 * ### 핵심 통찰 (캐시 가능성)
 * 빌트인 단타 프리셋(`DAYTRADE_*`)은 **userId 무관**이며, **같은 거래일이면 일봉 기반 결과가 동일**하다.
 * 따라서 캐시 키를 `(presetKey, filterHash, interval, limit, tradingDay)`로 잡으면
 * 첫 호출만 universe 2400+ × DSL 평가(약 28s)를 수행하고, 이후 동일 거래일 호출은 캐시 반환한다.
 *
 * ### 캐시 대상 = 발굴 계산 결과만 (side-effect 제외)
 * `realtimeWatchRegistered`(STRATEGY owner WS 수요 발행)는 호출별 side-effect이므로 캐시하지 않는다.
 * 서비스는 캐시된 발굴 결과(matches·skip 매트릭스)를 받고, realtime watch 발행은 매 호출 재수행한다.
 * → 캐시는 무거운 read-only 계산(28s)만 흡수하고, 가벼운 owner 발행은 그대로 동작 (watchlist write 0 LOCK 유지).
 *
 * ### Redis 키
 * `screener:daytrade:{presetKey}:{filterHash}:{interval}:{limit}:{tradingDay}` TTL [ttlMinutes]분.
 * 거래일이 키에 포함 → 새 거래일 자동 무효화. TTL은 장중 신규 일봉(전일 종가 확정) 반영 안전망.
 *
 * ### 가드 정합
 * - AI 호출 0 / mock 0 — pure Redis JSON cache.
 * - owner 가드: 캐시는 matches만 보관, watchlist write 경로 0 (서비스가 발행 책임 유지).
 */
@Component
class DaytradeDiscoverCache(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.daytrade.discovery.cache-ttl-minutes:60}") private val ttlMinutes: Long,
    @Value("\${magicjar.daytrade.discovery.cache-enabled:true}") private val cacheEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(DaytradeDiscoverCache::class.java)

    /**
     * 캐시 조회. HIT 시 [DaytradeDiscoverResultDto] (계산 결과만 — realtimeWatchRegistered=0).
     */
    fun get(key: String): DaytradeDiscoverResultDto? {
        if (!cacheEnabled) return null
        val json =
            redisTemplate.opsForValue().get(key) ?: run {
                log.debug("daytrade discover cache MISS: key={}", key)
                return null
            }
        return runCatching {
            objectMapper.readValue<DaytradeDiscoverResultDto>(json)
        }.getOrElse { ex ->
            log.warn("daytrade discover cache 역직렬화 실패: key={} err={}", key, ex.message)
            null
        }.also {
            if (it != null) log.debug("daytrade discover cache HIT: key={}", key)
        }
    }

    /**
     * 캐시 저장 (TTL 적용). 발굴 계산 결과만 — realtimeWatchRegistered=0 으로 normalize 후 저장.
     */
    fun put(
        key: String,
        result: DaytradeDiscoverResultDto,
    ) {
        if (!cacheEnabled) return
        // side-effect 카운트는 캐시에 보관하지 않음 (호출별 재계산)
        val cacheable = result.copy(realtimeWatchRegistered = 0)
        val json = objectMapper.writeValueAsString(cacheable)
        redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(ttlMinutes))
        log.debug("daytrade discover cache PUT: key={} matchedCount={} ttlMin={}", key, result.matchedCount, ttlMinutes)
    }

    /**
     * userId 무관 캐시 키 — `(presetKey, filterHash, interval, limit, tradingDay)`.
     *
     * @param presetKey null이면 default DAYTRADE_* 합집합을 나타내는 sentinel 사용.
     * @param filterFingerprint markets·types·marketCapMin·avgVolumeMin 등 universe 필터 식별 문자열.
     */
    fun buildKey(
        presetKey: String?,
        filterFingerprint: String,
        interval: String,
        limit: Int,
        tradingDay: LocalDate = LocalDate.now(SEOUL),
    ): String {
        val preset = presetKey ?: "DEFAULT_DAYTRADE_UNION"
        val filterHash = crc32(filterFingerprint)
        return RedisKeyRegistry.Screener.daytrade(preset, filterHash, interval, limit, tradingDay.toString())
    }

    private fun crc32(value: String): String {
        val crc = CRC32()
        crc.update(value.toByteArray(Charsets.UTF_8))
        return crc.value.toString(16)
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
