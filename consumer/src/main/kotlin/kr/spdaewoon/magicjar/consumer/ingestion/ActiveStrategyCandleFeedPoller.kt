package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.market.LiveCandleUpdater
import kr.spdaewoon.magicjar.consumer.order.PositionStopLossTickWatcher
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.port.KisMultiPricePort
import kr.spdaewoon.magicjar.domain.market.port.MultiPriceQuote
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.datetime.Instant as KxInstant

/**
 * ACTIVE 전략 전용 degraded candle feed.
 *
 * WS 구독은 성공했지만 H0UNCNT0 tick이 조용하거나 Redis 1m 진행봉이 비어 있으면,
 * KIS 멀티시세(최대 30종목/1콜)로 현재가를 샘플링해 Redis 진행봉을 보강한다.
 * 전체 watchlist polling은 되살리지 않는다.
 */
@Component
class ActiveStrategyCandleFeedPoller(
    private val activeStrategyCodeProvider: ActiveStrategyCodeProvider,
    private val kisMultiPricePort: KisMultiPricePort,
    private val liveCandleUpdater: LiveCandleUpdater,
    private val positionStopLossTickWatcher: PositionStopLossTickWatcher,
    private val kisWebSocketBridge: KisWebSocketBridge,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${magicjar.realtime-candle.degraded-poller.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.realtime-candle.degraded-poller.stale-after-seconds:10}")
    private val staleAfterSeconds: Long = 10L,
    @Value("\${magicjar.realtime-candle.degraded-poller.max-codes-per-cycle:30}")
    private val maxCodesPerCycle: Int = 30,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ActiveStrategyCandleFeedPoller::class.java)
    private val kstZone = ZoneId.of("Asia/Seoul")
    private val lastAccumulatedVolumeByCode = ConcurrentHashMap<String, Long>()
    private val pollCursor = AtomicInteger(0)

    @Scheduled(
        fixedDelayString = "\${magicjar.realtime-candle.degraded-poller.fixed-delay-ms:5000}",
        initialDelayString = "\${magicjar.realtime-candle.degraded-poller.initial-delay-ms:7000}",
    )
    fun poll() {
        if (!enabled || staleAfterSeconds <= 0L) return
        if (!isIntegratedSessionNow()) return

        val codes = activeStrategyCodeProvider.activeCodes()
        if (codes.isEmpty()) {
            lastAccumulatedVolumeByCode.clear()
            pollCursor.set(0)
            return
        }

        val staleCandidates =
            codes
                .asSequence()
                .filter { shouldPoll(it) }
                .toList()
        val candidates = selectCandidates(staleCandidates)
        if (candidates.isEmpty()) return

        val quotes =
            runCatching {
                kisMultiPricePort.fetchMultiPrice(
                    codes = candidates,
                    mrktDivCode = MRKT_DIV_CODE_KRX,
                    priority = KisCallPriority.REALTIME_MARKET,
                )
            }.onFailure { e ->
                log.warn("ACTIVE 전략 degraded candle poll 실패: codes={} err={}", candidates.size, e.message)
            }.getOrDefault(emptyList())
        if (quotes.isEmpty()) return

        val now = KxInstant.fromEpochMilliseconds(Instant.now(clock).toEpochMilli())
        var updated = 0
        quotes.forEach { quote ->
            val tick = quote.toPollingTick(now) ?: return@forEach
            liveCandleUpdater.update(tick)
            positionStopLossTickWatcher.onFallbackPriceTick(tick)
            updated++
        }
        if (updated > 0) {
            log.debug(
                "ACTIVE 전략 degraded candle poll 반영: requested={} updated={} codes={}",
                candidates.size,
                updated,
                candidates,
            )
        }
    }

    private fun shouldPoll(code: String): Boolean {
        val current1mKey = RedisKeyRegistry.Market.candleCurrent(code, "1m")
        val hasCurrent1m = redisTemplate.hasKey(current1mKey) == true
        val tickAgeSeconds = kisWebSocketBridge.lastTickAgeSeconds(code)

        return !hasCurrent1m || tickAgeSeconds == null || tickAgeSeconds >= staleAfterSeconds
    }

    private fun selectCandidates(candidates: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val limit = maxCodesPerCycle.coerceAtLeast(1)
        if (candidates.size <= limit) return candidates

        val start = Math.floorMod(pollCursor.getAndAdd(limit), candidates.size)
        return (0 until limit).map { offset -> candidates[(start + offset) % candidates.size] }
    }

    private fun MultiPriceQuote.toPollingTick(now: KxInstant): TickEvent? {
        val price = close?.takeIf { it > BigDecimal.ZERO } ?: return null
        val deltaVolume = accumulatedVolumeDelta(code, accumulatedVolume)
        return TickEvent(
            code = code,
            price = price,
            volume = deltaVolume,
            ts = now,
            source = SOURCE_KIS_POLLING,
            stale = true,
        )
    }

    private fun accumulatedVolumeDelta(
        code: String,
        accumulatedVolume: Long?,
    ): Long {
        if (accumulatedVolume == null || accumulatedVolume < 0L) return 0L
        val previous = lastAccumulatedVolumeByCode.put(code, accumulatedVolume)
        if (previous == null || accumulatedVolume < previous) return 0L
        return accumulatedVolume - previous
    }

    private fun isIntegratedSessionNow(): Boolean {
        val nowKst = Instant.now(clock).atZone(kstZone)
        val minOfDay = nowKst.hour * 60 + nowKst.minute
        return CandleSession.isInIntegratedSession(minOfDay)
    }

    companion object {
        const val SOURCE_KIS_POLLING = PositionStopLossTickWatcher.SOURCE_KIS_POLLING
        private const val MRKT_DIV_CODE_KRX = "J"
    }
}
