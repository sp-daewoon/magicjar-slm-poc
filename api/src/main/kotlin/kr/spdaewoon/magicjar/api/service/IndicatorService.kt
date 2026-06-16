package kr.spdaewoon.magicjar.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.controller.IndicatorResultDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * 지표 계산 서비스 — cache-aside 패턴 (spec §6-1 호출 시퀀스 LOCK).
 *
 * 1. Redis 캐시 조회 (indicator:cache:{code}:{interval}:{name}:{paramsHash}:{rangeHash})
 * 2. miss → DB candle 조회 → IndicatorEngine.calculate()
 * 3. 결과 Redis 저장 (분봉 5분 / 일봉 1일 TTL — spec §6-4 LOCK)
 *
 * IndicatorEngine은 api 모듈 IndicatorEngineConfig에서 bean 등록 (sources stateless — spec §3-3).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class IndicatorService(
    private val indicatorEngine: IndicatorEngine,
    private val candleJpaRepository: CandleJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(IndicatorService::class.java)

    /** 분봉 interval set — TTL 5분 적용 (spec §6-4 TTL 매트릭스) */
    private val intraIntervals = setOf("1m", "2m", "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h")

    /**
     * 지표 계산 메인 메서드.
     *
     * @throws ResponseStatusException 400 — 미지원 지표 / 캔들 부족 / 잘못된 파라미터
     */
    fun calculate(
        code: String,
        name: String,
        interval: String,
        paramsRaw: String?,
        from: String?,
        to: String?,
        adjusted: Boolean,
    ): IndicatorResultDto {
        validateCode(code)
        validateName(name)

        val params = parseParams(paramsRaw)
        val spec = IndicatorSpec(name = name, params = params)
        val cacheKey = buildCacheKey(code, interval, name, spec.paramsHash(), from, to, adjusted)

        // 1. Redis 캐시 hit 확인
        val cached = tryGetCache(cacheKey)
        if (cached != null) {
            log.debug("indicator cache HIT: key={}", cacheKey)
            return cached
        }
        log.debug("indicator cache MISS: key={}", cacheKey)

        // 2. DB candle 조회
        val candles = loadCandles(code, interval, from, to, adjusted)

        // 3. 지표 계산
        val result =
            try {
                indicatorEngine.calculate(spec, candles)
            } catch (e: IndicatorException.UnsupportedSpecException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
            } catch (e: IndicatorException.InsufficientCandlesException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
            } catch (e: IndicatorException.CalculationFailureException) {
                log.error("indicator 계산 실패: code={} name={} err={}", code, name, e.message)
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "지표 계산 오류: ${e.message}")
            }

        val dto = IndicatorResultDto.from(result)

        // 4. Redis 캐시 저장
        tryPutCache(cacheKey, interval, dto)

        return dto
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종목코드는 6자리 숫자여야 합니다 (예: 005930)")
        }
    }

    private fun validateName(name: String) {
        if (name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지표 name은 필수입니다")
        }
    }

    /**
     * paramsRaw 문자열 파싱.
     * 형식: "period=14" 또는 "fast=12,slow=26,signal=9" (쉼표 구분 key=value).
     */
    private fun parseParams(paramsRaw: String?): Map<String, Any> {
        if (paramsRaw.isNullOrBlank()) return emptyMap()
        return paramsRaw
            .split(",")
            .mapNotNull { entry ->
                val parts = entry.trim().split("=")
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (k.isNotBlank() && v.isNotBlank()) {
                        k to (v.toIntOrNull() ?: v.toBigDecimalOrNull() ?: v)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }.toMap()
    }

    /**
     * DB candle 조회 (from·to 범위 또는 최신 500건).
     * adjusted=true 시 adj 가격 사용 (T-CA1 정합).
     *
     * ADR 0051 — CandleJpaRepository interface body fun 금지 준수:
     * 기존 메서드(findByCodeAndIntervalAndTsBefore / findByCodeAndIntervalLatest)를 활용,
     * from 필터는 service 레이어 post-filter 적용.
     */
    private fun loadCandles(
        code: String,
        interval: String,
        from: String?,
        to: String?,
        adjusted: Boolean,
    ): List<Candle> {
        val intervalEnum =
            try {
                CandleInterval.fromCode(interval)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 interval: $interval")
            }

        val fromInstant: Instant? = from?.let { parseJavaInstant(it) }
        val toInstant: Instant? = to?.let { parseJavaInstant(it) }

        val rawEntities =
            if (fromInstant != null && toInstant != null) {
                candleJpaRepository.findByCodeAndIntervalAndTsBetween(
                    code,
                    interval,
                    fromInstant,
                    toInstant,
                )
            } else if (toInstant != null) {
                candleJpaRepository.findByCodeAndIntervalAndTsBefore(
                    code,
                    interval,
                    toInstant,
                    PageRequest.of(0, 1000),
                )
            } else {
                candleJpaRepository.findByCodeAndIntervalLatest(
                    code,
                    interval,
                    PageRequest.of(0, 500),
                )
            }

        val filtered =
            if (fromInstant != null) {
                rawEntities.filter { entity -> !entity.ts.isBefore(fromInstant) }
            } else {
                rawEntities
            }

        return filtered
            .map { entity ->
                val useAdj = adjusted && entity.closeAdj != null
                Candle(
                    id = entity.id,
                    code = entity.code,
                    interval = intervalEnum,
                    ts = entity.ts.toKotlinInstant(),
                    openRaw =
                        if (useAdj) {
                            entity.openAdj ?: entity.openRaw ?: java.math.BigDecimal.ZERO
                        } else {
                            entity.openRaw
                                ?: java.math.BigDecimal.ZERO
                        },
                    highRaw =
                        if (useAdj) {
                            entity.highAdj ?: entity.highRaw ?: java.math.BigDecimal.ZERO
                        } else {
                            entity.highRaw
                                ?: java.math.BigDecimal.ZERO
                        },
                    lowRaw =
                        if (useAdj) {
                            entity.lowAdj ?: entity.lowRaw ?: java.math.BigDecimal.ZERO
                        } else {
                            entity.lowRaw
                                ?: java.math.BigDecimal.ZERO
                        },
                    closeRaw =
                        if (useAdj) {
                            entity.closeAdj ?: entity.closeRaw ?: java.math.BigDecimal.ZERO
                        } else {
                            entity.closeRaw
                                ?: java.math.BigDecimal.ZERO
                        },
                    openAdj = entity.openAdj,
                    highAdj = entity.highAdj,
                    lowAdj = entity.lowAdj,
                    closeAdj = entity.closeAdj,
                    volume = entity.volume ?: 0L,
                    source = entity.source,
                )
            }.sortedBy { it.ts }
    }

    private fun parseJavaInstant(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 시각 형식: $value (ISO 8601 UTC 필요, 예: 2024-01-01T00:00:00Z)")
        }

    /** Redis 캐시 key: indicator:cache:{code}:{interval}:{name}:{paramsHash}:{rangeHash} (spec §6-4 LOCK) */
    private fun buildCacheKey(
        code: String,
        interval: String,
        name: String,
        paramsHash: String,
        from: String?,
        to: String?,
        adjusted: Boolean,
    ): String = RedisKeyRegistry.Indicator.resultWithRange(code, interval, name, paramsHash, rangeHash(from, to, adjusted))

    private fun rangeHash(
        from: String?,
        to: String?,
        adjusted: Boolean,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${from.orEmpty()}|${to.orEmpty()}|adjusted=$adjusted".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** TTL 결정: 분봉 300s / 일봉 이상 86400s (spec §6-4 TTL 매트릭스 LOCK) */
    private fun ttlFor(interval: String): Duration =
        if (interval in intraIntervals) Duration.ofSeconds(300L) else Duration.ofSeconds(86400L)

    private fun tryGetCache(key: String): IndicatorResultDto? =
        try {
            val json = redisTemplate.opsForValue().get(key)
            if (json != null) objectMapper.readValue(json, IndicatorResultDto::class.java) else null
        } catch (e: Exception) {
            log.debug("cache get 실패: key={} err={}", key, e.message)
            null
        }

    private fun tryPutCache(
        key: String,
        interval: String,
        dto: IndicatorResultDto,
    ) {
        try {
            val json = objectMapper.writeValueAsString(dto)
            redisTemplate.opsForValue().set(key, json, ttlFor(interval))
            log.debug("indicator cache SET: key={} ttl={}s", key, ttlFor(interval).seconds)
        } catch (e: Exception) {
            log.debug("cache set 실패: key={} err={}", key, e.message)
        }
    }
}
