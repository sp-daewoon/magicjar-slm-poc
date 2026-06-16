package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.risk.port.TradeFrequencyGuard
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * 거래 빈도 sliding window 가드 — Redis ZSET 구현 (ADR 0067 §7-1 + D-1 정합).
 *
 * key:
 *   - `risk:trade-freq:{strategyId}:{userId}:minute`  (TTL 5분)
 *   - `risk:trade-freq:{strategyId}:{userId}:day`     (TTL 25시간 — 자정 reset cover)
 * score = epoch second · member = signalId.toString()
 * count = `ZCOUNT(key, from, now)` — O(log N).
 *
 * ## 일 카운트 = 당일(KST) 경계 (canary D2 hotfix #3 — runbook·RiskConfig "당일" 의도 정합)
 * minute 는 롤링 60s 윈도 그대로. day 는 **롤링 24h 가 아니라 당일(KST 자정~now)** 으로 절단한다.
 * 과거엔 `from = now - 86400`(롤링 24h)이라 어제 거래가 오늘 한도를 잠식했다(canary D2 — 어제 막판 거래가
 * 오늘 아침 한도에 그대로 계상). [kstMidnightEpoch] 로 `from` 하한을 당일 0시(KST)로 끌어올려, 자정이 지나면
 * 어제 score 는 윈도 밖으로 빠진다(member 도 record 시 당일 0시 미만은 trim).
 *
 * count 조회는 fail-closed 정책이다. Redis 결함 시 매우 큰 count를 반환해 빈도 제한 룰이 주문을 차단한다.
 *
 * spec 인용: ADR 0067 §5-5 + §7-1 TradeFrequencyRedisGuard.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class TradeFrequencyRedisGuard(
    private val redisTemplate: StringRedisTemplate,
) : TradeFrequencyGuard {
    private val log = LoggerFactory.getLogger(TradeFrequencyRedisGuard::class.java)

    companion object {
        private const val MINUTE_WINDOW_SEC = 60L
        private const val DAY_WINDOW_SEC = 86400L

        private val MINUTE_KEY_TTL = Duration.ofMinutes(5)
        private val DAY_KEY_TTL = Duration.ofHours(25)

        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** 당일(KST) 자정 epoch second — day 카운트 윈도 하한(롤링 24h 대신 당일 경계). */
        internal fun kstMidnightEpoch(now: Instant): Long =
            LocalDate
                .ofInstant(java.time.Instant.ofEpochSecond(now.epochSeconds), KST)
                .atStartOfDay(KST)
                .toEpochSecond()
    }

    override fun countRecentMinute(
        strategyId: Long,
        userId: String,
        now: Instant,
    ): Int = count(buildKey(strategyId, userId, "minute"), now, MINUTE_WINDOW_SEC)

    /**
     * 당일(KST) 누적 거래 수 — `from = max(now - 86400, 당일 0시 KST)`.
     * 자정 경계 절단으로 어제 거래가 오늘 한도에 누계되지 않는다(canary D2 hotfix #3).
     */
    override fun countRecentDay(
        strategyId: Long,
        userId: String,
        now: Instant,
    ): Int = count(buildKey(strategyId, userId, "day"), now, DAY_WINDOW_SEC, floorEpoch = kstMidnightEpoch(now))

    override fun record(
        strategyId: Long,
        userId: String,
        signalId: Long,
        now: Instant,
    ) {
        try {
            val score = now.epochSeconds.toDouble()
            val member = signalId.toString()
            val zops = redisTemplate.opsForZSet()

            val minuteKey = buildKey(strategyId, userId, "minute")
            zops.add(minuteKey, member, score)
            zops.removeRangeByScore(minuteKey, 0.0, score - MINUTE_WINDOW_SEC)
            redisTemplate.expire(minuteKey, MINUTE_KEY_TTL)

            val dayKey = buildKey(strategyId, userId, "day")
            zops.add(dayKey, member, score)
            // 당일(KST) 경계 미만 score 는 trim — 자정 지나면 어제 거래가 카운트에서 빠진다(hotfix #3).
            // 롤링 24h trim(score - DAY_WINDOW_SEC)과 당일 0시 중 더 보수적인(큰) 하한을 사용.
            val dayFloor = maxOf(score - DAY_WINDOW_SEC, kstMidnightEpoch(now).toDouble())
            zops.removeRangeByScore(dayKey, 0.0, dayFloor)
            redisTemplate.expire(dayKey, DAY_KEY_TTL)

            log.debug(
                "TradeFrequencyRedisGuard record: strategyId={} userId={} signalId={} ts={}",
                strategyId,
                userId,
                signalId,
                now,
            )
        } catch (e: Exception) {
            log.warn(
                "TradeFrequencyRedisGuard record 실패 (fail-open): strategyId={} userId={} err={}",
                strategyId,
                userId,
                e.message,
            )
        }
    }

    override fun removeRecord(
        strategyId: Long,
        userId: String,
        signalId: Long,
    ) {
        try {
            val member = signalId.toString()
            val zops = redisTemplate.opsForZSet()
            val minuteKey = buildKey(strategyId, userId, "minute")
            val dayKey = buildKey(strategyId, userId, "day")

            zops.remove(minuteKey, member)
            zops.remove(dayKey, member)

            log.debug(
                "TradeFrequencyRedisGuard removeRecord: strategyId={} userId={} signalId={}",
                strategyId,
                userId,
                signalId,
            )
        } catch (e: Exception) {
            log.warn(
                "TradeFrequencyRedisGuard removeRecord 실패 (fail-open): strategyId={} userId={} signalId={} err={}",
                strategyId,
                userId,
                signalId,
                e.message,
            )
        }
    }

    /**
     * ZSET sliding window count. [floorEpoch] 지정 시 `from = max(now - windowSec, floorEpoch)` —
     * day 카운트의 당일(KST) 경계 하한(hotfix #3). 미지정 시 순수 롤링 윈도(minute).
     */
    private fun count(
        key: String,
        now: Instant,
        windowSec: Long,
        floorEpoch: Long? = null,
    ): Int =
        try {
            val nowEpoch = now.epochSeconds.toDouble()
            val rollingFrom = nowEpoch - windowSec
            val fromEpoch = floorEpoch?.let { maxOf(rollingFrom, it.toDouble()) } ?: rollingFrom
            val result = redisTemplate.opsForZSet().count(key, fromEpoch, nowEpoch)
            result?.toInt() ?: 0
        } catch (e: Exception) {
            log.warn("TradeFrequencyRedisGuard count 실패 (fail-closed): key={} err={}", key, e.message)
            Int.MAX_VALUE
        }

    private fun buildKey(
        strategyId: Long,
        userId: String,
        window: String,
    ): String = RedisKeyRegistry.Risk.tradeFrequency(strategyId, userId, window)
}
