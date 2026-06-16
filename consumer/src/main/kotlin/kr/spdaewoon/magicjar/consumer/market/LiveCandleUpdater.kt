package kr.spdaewoon.magicjar.consumer.market

import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Redis 진행봉 갱신 단일 경로.
 *
 * WS tick(H0UNCNT0/H0STOUP0)과 ACTIVE 전략 degraded polling이 같은 OHLCV/TTL/source 규칙을 공유한다.
 * 1d는 기존과 동일하게 공식 seed가 없으면 tick-only 생성하지 않는다.
 */
@Component
class LiveCandleUpdater(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(LiveCandleUpdater::class.java)
    private val kstZone = ZoneId.of("Asia/Seoul")

    fun update(tick: TickEvent) {
        try {
            LIVE_CANDLE_INTERVALS.forEach { interval ->
                val start = liveCandleStart(tick, interval)
                updateLiveCandleHash(
                    key = RedisKeyRegistry.Market.candleCurrent(tick.code, interval),
                    interval = interval,
                    start = start,
                    tick = tick,
                    ttl = liveCandleTtl(interval),
                )

                // 기존 API/운영 키 호환. 신규 경로는 candle:current:{code}:1d를 우선 사용한다.
                if (interval == "1d") {
                    updateLiveCandleHash(
                        key = RedisKeyRegistry.Market.candleToday1d(tick.code),
                        interval = interval,
                        start = start,
                        tick = tick,
                        ttl = Duration.ofHours(24),
                    )
                }
            }
        } catch (e: Exception) {
            log.error("live candle 갱신 실패: code={} source={} error={}", tick.code, tick.source, e.message)
        }
    }

    private fun updateLiveCandleHash(
        key: String,
        interval: String,
        start: Instant,
        tick: TickEvent,
        ttl: Duration,
    ) {
        val existing = redisTemplate.opsForHash<String, String>().entries(key)
        val startTs = start.toString()
        val sameBar = (existing["startTs"] ?: existing["ts"]) == startTs
        val priceStr = tick.price.toPlainString()

        // 공식 KIS 시드가 먼저 Hash를 채웠는지. seeded=true 이면 open/고저/거래량 공식 baseline을 보존한다.
        val seeded = sameBar && existing["seeded"] == "true"
        if (interval == "1d" && !seeded) {
            log.debug("1d live candle tick-only 생성 skip — 공식 seed 대기: key={} startTs={}", key, startTs)
            return
        }

        val open =
            if (sameBar) {
                existing["open"] ?: priceStr
            } else {
                priceStr
            }
        val high =
            if (sameBar) {
                existing["high"]?.let { h -> maxOf(h.toBigDecimalOrNull() ?: tick.price, tick.price).toPlainString() } ?: priceStr
            } else {
                priceStr
            }
        val low =
            if (sameBar) {
                existing["low"]?.let { l -> minOf(l.toBigDecimalOrNull() ?: tick.price, tick.price).toPlainString() } ?: priceStr
            } else {
                priceStr
            }

        val baseFields: Map<String, String>
        val volume: Long
        if (seeded) {
            val acmlBase = existing["acmlBase"]?.toLongOrNull() ?: 0L
            val wsVolDelta = (existing["wsVolDelta"]?.toLongOrNull() ?: 0L) + tick.volume
            volume = acmlBase + wsVolDelta
            baseFields = mapOf("seeded" to "true", "acmlBase" to acmlBase.toString(), "wsVolDelta" to wsVolDelta.toString())
        } else {
            volume = (if (sameBar) existing["volume"]?.toLongOrNull() ?: 0L else 0L) + tick.volume
            baseFields = emptyMap()
        }

        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "interval" to interval,
                "open" to open,
                "high" to high,
                "low" to low,
                "close" to priceStr,
                "volume" to volume.toString(),
                "startTs" to startTs,
                "ts" to startTs,
                "source" to tick.source,
                "updatedAt" to tick.ts.toString(),
            ) + baseFields,
        )
        redisTemplate.expire(key, ttl)
    }

    private fun liveCandleStart(
        tick: TickEvent,
        interval: String,
    ): Instant {
        val tickInstant = Instant.ofEpochMilli(tick.ts.toEpochMilliseconds())
        val zdt = tickInstant.atZone(kstZone)
        val dayStart = zdt.toLocalDate().atStartOfDay(kstZone)
        val minOfDay = zdt.hour * 60 + zdt.minute

        return when (interval) {
            "1m" -> zdt.truncatedTo(ChronoUnit.MINUTES).toInstant()
            "3m" -> floorKstMinute(dayStart, minOfDay, 3)
            "5m" -> floorKstMinute(dayStart, minOfDay, 5)
            "15m" -> floorKstMinute(dayStart, minOfDay, 15)
            "30m" -> floorKstMinute(dayStart, minOfDay, 30)
            "90m" -> dayStart.plusMinutes(ninetyMinuteStart(minOfDay).toLong()).toInstant()
            "1h" -> zdt.truncatedTo(ChronoUnit.HOURS).toInstant()
            "4h" -> anchoredIntegratedStart(dayStart, minOfDay, 4 * 60)
            "12h" -> dayStart.plusMinutes(NXT_INTEGRATED_OPEN_MIN.toLong()).toInstant()
            "1d" -> dayStart.toInstant()
            "1wk" -> {
                val monday = zdt.toLocalDate().minusDays((zdt.dayOfWeek.value - 1).toLong())
                monday.atStartOfDay(kstZone).toInstant()
            }
            "1mo" -> LocalDate.of(zdt.year, zdt.month, 1).atStartOfDay(kstZone).toInstant()
            "1y" -> LocalDate.of(zdt.year, 1, 1).atStartOfDay(kstZone).toInstant()
            else -> dayStart.toInstant()
        }
    }

    private fun floorKstMinute(
        dayStart: java.time.ZonedDateTime,
        minOfDay: Int,
        intervalMinutes: Int,
    ): Instant {
        val startMinute = (minOfDay / intervalMinutes) * intervalMinutes
        return dayStart.plusMinutes(startMinute.toLong()).toInstant()
    }

    private fun anchoredIntegratedStart(
        dayStart: java.time.ZonedDateTime,
        minOfDay: Int,
        intervalMinutes: Int,
    ): Instant {
        val boundedMinute = minOfDay.coerceAtLeast(NXT_INTEGRATED_OPEN_MIN)
        val startMinute =
            NXT_INTEGRATED_OPEN_MIN +
                ((boundedMinute - NXT_INTEGRATED_OPEN_MIN) / intervalMinutes) * intervalMinutes
        return dayStart.plusMinutes(startMinute.toLong()).toInstant()
    }

    private fun ninetyMinuteStart(minOfDay: Int): Int {
        var picked = NINETY_MIN_BOUNDARY_STARTS.first()
        for (anchor in NINETY_MIN_BOUNDARY_STARTS) {
            if (anchor <= minOfDay) picked = anchor else break
        }
        return picked
    }

    private fun liveCandleTtl(interval: String): Duration =
        when (interval) {
            "1m" -> Duration.ofMinutes(2)
            "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h" -> Duration.ofHours(24)
            "1d" -> Duration.ofDays(2)
            "1wk" -> Duration.ofDays(14)
            "1mo" -> Duration.ofDays(62)
            "1y" -> Duration.ofDays(400)
            else -> Duration.ofDays(2)
        }

    companion object {
        val LIVE_CANDLE_INTERVALS =
            listOf("1m", "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h", "1d", "1wk", "1mo", "1y")

        private const val NXT_INTEGRATED_OPEN_MIN = CandleSession.NXT_INTEGRATED_OPEN_MIN
        private val NINETY_MIN_BOUNDARY_STARTS = CandleSession.NINETY_MIN_START_MIN.toList()
    }
}
