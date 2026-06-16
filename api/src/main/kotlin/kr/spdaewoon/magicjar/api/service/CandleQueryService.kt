package kr.spdaewoon.magicjar.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.config.ChartCandleProperties
import kr.spdaewoon.magicjar.api.controller.CandleDto
import kr.spdaewoon.magicjar.api.controller.CandlesResponseDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.TodayCandleSeedWindow
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 캔들 시계열 조회 서비스 — DB candle + Redis live current 합류 (spec §3-3, ADR 0019·0029).
 *
 * 인터벌별 source:
 * - 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y:
 *   DB candle + Redis market:candle:current:code:{code}:interval:{interval} (장중 마지막 미확정 bar)
 * - 1d seeded session: Redis market:candle:session:code:{code}:interval:1d
 *
 * 페이지네이션: before(ISO8601) cursor 기반 — before 이전 ts DESC LIMIT limit.
 * 응답: ts ASC 정렬 (LWC v4 setData() 기대).
 * hasMore: 응답 row count == limit
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class CandleQueryService(
    private val candleJpaRepository: CandleJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val chartCandleProperties: ChartCandleProperties,
) {
    private val log = LoggerFactory.getLogger(CandleQueryService::class.java)
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")

    val supportedIntervals = setOf("1m", "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h", "1d", "1wk", "1mo", "1y")
    val defaultLimit = 600

    /**
     * `?limit=` 사용자 입력 cap — ADR 0093 §3-1 LOCK (500 → 5000 확장).
     *
     * property override: `magicjar.candle.max-response-limit` (default 5000).
     * clamp: 사용자 입력 limit는 `coerceIn(1, maxLimit)` 적용 — 5000 초과 시 5000으로 cap.
     */
    val maxLimit: Int
        get() = chartCandleProperties.maxResponseLimit

    // interval → DB candle interval code 매핑 (DB CHECK constraint 11종 중 API 노출 8종)
    private val intervalCodeMap =
        mapOf(
            "1m" to "1m",
            "3m" to "3m",
            "5m" to "5m",
            "15m" to "15m",
            "30m" to "30m",
            "90m" to "90m",
            "1h" to "1h",
            "4h" to "4h",
            "12h" to "12h",
            "1d" to "1d",
            "1wk" to "1wk",
            "1mo" to "1mo",
            "1y" to "1y",
        )

    fun getCandles(
        code: String,
        interval: String,
        before: String?,
        limit: Int,
    ): CandlesResponseDto {
        val effectiveInterval = if (interval in supportedIntervals) interval else "1d"
        val effectiveLimit = limit.coerceIn(1, maxLimit)
        val dbInterval = intervalCodeMap[effectiveInterval] ?: effectiveInterval

        // DB 조회
        val beforeInstant =
            if (before != null) {
                try {
                    Instant.parse(before)
                } catch (e: Exception) {
                    log.warn("before 파싱 실패: before={} — 최신 기준 사용", before)
                    null
                }
            } else {
                null
            }

        val pageable = PageRequest.of(0, effectiveLimit)

        val dbRows =
            if (beforeInstant != null) {
                candleJpaRepository.findByCodeAndIntervalAndTsBefore(code, dbInterval, beforeInstant, pageable)
            } else {
                candleJpaRepository.findByCodeAndIntervalLatest(code, dbInterval, pageable)
            }

        // DB rows → CandleDto 변환 (DESC → ASC 변환)
        val sortedRows = dbRows.sortedBy { it.ts }
        val candleDtos =
            sortedRows
                .mapIndexed { index, entity ->
                    toCandleDto(entity, sortedRows.getOrNull(index - 1)?.closeRaw)
                }.toMutableList()

        // Redis current 합류 (장중 마지막 미확정 bar)
        val redisCandle =
            listOfNotNull(
                RedisKeyRegistry.Market.candleCurrent(code, effectiveInterval),
                if (effectiveInterval == "1d") RedisKeyRegistry.Market.candleToday1d(code) else null,
            ).asSequence()
                .mapNotNull { key -> readRedisCandleHash(key)?.let { key to it } }
                .firstOrNull { (key, candle) -> isTrustedRedisCandle(effectiveInterval, key, candle) }
                ?.second

        if (redisCandle != null && beforeInstant == null) {
            // before 없는 최신 요청일 때만 합류 (페이지네이션 시 제외)
            val redisTs = redisCandle["startTs"] ?: redisCandle["ts"]
            if (redisTs != null) {
                // DB 마지막 candle과 중복 방지 (같은 ts이면 replace)
                val redisInstant =
                    try {
                        Instant.parse(redisTs)
                    } catch (_: Exception) {
                        null
                    }
                if (redisInstant != null) {
                    val lastDbTs =
                        candleDtos.lastOrNull()?.ts?.let {
                            try {
                                Instant.parse(it)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    // hotfix 2026-05-15: same ts 일 때 replace 패턴 (자본 보호 critical sync).
                    // DB 마지막 row 와 Redis ts 가 같으면 (오늘 진행 중 1d 또는 1m) Redis 값으로 덮어쓰기.
                    // 이전 > 만 add → same ts 시 skip → 차트 + 자동매매 stale DB close 사용 risk.
                    val sameTsCandle = if (redisInstant == lastDbTs) candleDtos.lastOrNull() else null
                    val redisCandleDto = mergeRedisCandle(redisTs, redisCandle, sameTsCandle, candleDtos.lastOrNull())
                    if (lastDbTs == null || redisInstant > lastDbTs) {
                        candleDtos.add(redisCandleDto)
                    } else if (redisInstant == lastDbTs && candleDtos.isNotEmpty()) {
                        candleDtos[candleDtos.size - 1] = redisCandleDto
                    }
                }
            }
        }

        if (effectiveInterval in QUOTE_CURRENT_CANDLE_INTERVALS && beforeInstant == null) {
            mergeQuoteLastCurrentCandle(code, effectiveInterval, candleDtos)
        }

        val hasMore = dbRows.size == effectiveLimit
        val nextBefore = if (hasMore && dbRows.isNotEmpty()) dbRows.last().ts.toString() else null

        return CandlesResponseDto(
            code = code,
            interval = effectiveInterval,
            candles = candleDtos,
            hasMore = hasMore,
            nextBefore = nextBefore,
        )
    }

    private fun toCandleDto(
        entity: CandleJpaEntity,
        previousClose: BigDecimal?,
    ): CandleDto {
        val close = entity.closeRaw ?: entity.closeAdj
        val change = if (close != null && previousClose != null) close - previousClose else null
        return CandleDto(
            ts = entity.ts.toString(),
            open = (entity.openRaw ?: entity.openAdj)?.toPlainString() ?: "0",
            high = (entity.highRaw ?: entity.highAdj)?.toPlainString() ?: "0",
            low = (entity.lowRaw ?: entity.lowAdj)?.toPlainString() ?: "0",
            close = close?.toPlainString() ?: "0",
            volume = entity.volume ?: 0L,
            previousClose = previousClose?.toPlainString(),
            change = change?.toPlainString(),
            changeRate = formatChangeRate(change, previousClose),
        )
    }

    private fun mergeRedisCandle(
        redisTs: String,
        redisCandle: Map<String, String>,
        sameTsCandle: CandleDto?,
        previousForMetrics: CandleDto?,
    ): CandleDto {
        val redisClose = redisCandle["close"] ?: "0"
        val close = redisClose.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val redisOpen = redisCandle["open"] ?: redisClose
        val redisHigh = redisCandle["high"]?.toBigDecimalOrNull() ?: close
        val redisLow = redisCandle["low"]?.toBigDecimalOrNull() ?: close

        val high =
            sameTsCandle
                ?.high
                ?.toBigDecimalOrNull()
                ?.let { maxOf(it, redisHigh, close) }
                ?: redisHigh
        val low =
            sameTsCandle
                ?.low
                ?.toBigDecimalOrNull()
                ?.let { minOf(it, redisLow, close) }
                ?: redisLow

        return CandleDto(
            ts = redisTs,
            open = sameTsCandle?.open ?: redisOpen,
            high = high.toPlainString(),
            low = low.toPlainString(),
            close = redisClose,
            volume = maxOf(sameTsCandle?.volume ?: 0L, redisCandle["volume"]?.toLongOrNull() ?: 0L),
            previousClose = previousForMetrics?.previousClose,
            change = previousForMetrics?.change,
            changeRate = previousForMetrics?.changeRate,
        )
    }

    private fun mergeQuoteLastCurrentCandle(
        code: String,
        interval: String,
        candleDtos: MutableList<CandleDto>,
    ) {
        val latest = candleDtos.lastOrNull() ?: return
        val quoteMap = readQuoteLast(code)
        val metrics = resolveQuoteChangeMetrics(quoteMap) ?: return
        val quoteTs = quoteMap["ts"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return
        val latestTs = runCatching { Instant.parse(latest.ts) }.getOrNull() ?: return
        if (!isSameQuotePeriod(interval, quoteTs, latestTs)) return

        val price = metrics.price
        val high = maxOf(latest.high.toBigDecimalOrNull() ?: price, price)
        val low = minOf(latest.low.toBigDecimalOrNull() ?: price, price)
        val quoteMetrics =
            if (interval == "1d") {
                QuoteDisplayMetrics(
                    previousClose = metrics.previousClose,
                    change = metrics.signedChange,
                    changeRate = metrics.signedChangeRate.setScale(2, RoundingMode.HALF_UP),
                )
            } else {
                resolvePeriodChangeMetrics(price, latest.previousClose)
            }
        candleDtos[candleDtos.lastIndex] =
            latest.copy(
                high = high.toPlainString(),
                low = low.toPlainString(),
                close = price.toPlainString(),
                previousClose = quoteMetrics?.previousClose?.toPlainString() ?: latest.previousClose,
                change = quoteMetrics?.change?.toPlainString() ?: latest.change,
                changeRate = quoteMetrics?.changeRate?.toPlainString() ?: latest.changeRate,
            )
    }

    private fun isSameQuotePeriod(
        interval: String,
        quoteTs: Instant,
        latestTs: Instant,
    ): Boolean {
        val quoteDate = quoteTs.atZone(kst).toLocalDate()
        val latestDate = latestTs.atZone(kst).toLocalDate()
        return when (interval) {
            "1d" -> quoteDate == latestDate
            "1wk" -> weekStart(quoteDate) == weekStart(latestDate)
            "1mo" -> quoteDate.year == latestDate.year && quoteDate.month == latestDate.month
            "1y" -> quoteDate.year == latestDate.year
            else -> false
        }
    }

    private fun weekStart(date: java.time.LocalDate): java.time.LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun resolvePeriodChangeMetrics(
        price: BigDecimal,
        previousCloseRaw: String?,
    ): QuoteDisplayMetrics? {
        val previousClose = previousCloseRaw?.toBigDecimalOrNull() ?: return null
        val change = price - previousClose
        val changeRate = formatChangeRate(change, previousClose)?.toBigDecimalOrNull()
        return QuoteDisplayMetrics(previousClose, change, changeRate)
    }

    private data class QuoteDisplayMetrics(
        val previousClose: BigDecimal,
        val change: BigDecimal,
        val changeRate: BigDecimal?,
    )

    private fun readQuoteLast(code: String): Map<String, Any?> {
        val quoteJson =
            try {
                redisTemplate.opsForValue().get(RedisKeyRegistry.Market.quoteLast(code))
            } catch (e: Exception) {
                log.debug("quote:last 조회 실패: code={} err={}", code, e.message)
                null
            } ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(quoteJson, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            log.debug("quote:last 파싱 실패: code={} err={}", code, e.message)
            emptyMap()
        }
    }

    private fun formatChangeRate(
        change: BigDecimal?,
        previousClose: BigDecimal?,
    ): String? {
        if (change == null || previousClose == null || previousClose == BigDecimal.ZERO) return null
        return change
            .divide(previousClose, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun readRedisCandleHash(key: String): Map<String, String>? =
        try {
            val entries = redisTemplate.opsForHash<String, String>().entries(key)
            if (entries.isEmpty()) null else entries
        } catch (e: Exception) {
            log.debug("Redis candle hash 조회 실패: key={} err={}", key, e.message)
            null
        }

    private fun isTrustedRedisCandle(
        interval: String,
        key: String,
        candle: Map<String, String>,
    ): Boolean {
        if (interval != "1d") return true
        val trusted = candle["seeded"] == "true"
        if (!trusted) {
            log.debug("unseeded 1d Redis candle skip: key={}", key)
            return false
        }

        val redisTs = candle["startTs"] ?: candle["ts"]
        val redisInstant =
            redisTs?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
        val now = Instant.now()
        if (redisInstant != null && TodayCandleSeedWindow.shouldSkipRedisCandle(now, redisInstant, kst)) {
            log.info(
                "pre-open today 1d Redis candle skip: key={} redisTs={} nowKst={}",
                key,
                redisTs,
                now.atZone(kst),
            )
            return false
        }
        return true
    }

    private companion object {
        val QUOTE_CURRENT_CANDLE_INTERVALS = setOf("1d", "1wk", "1mo", "1y")
    }
}
