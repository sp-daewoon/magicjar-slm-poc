package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.risk.port.DailyPnlProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

/**
 * 일일 실현 P&L 산출기 — KST 자정 reset (ADR 0067 §7-1 + B-1 정합).
 *
 *  1. REAL 모드: 주문 전 KIS 체결 sync 이후 DB 체결 이력을 매번 재계산 (일중 손실 stale cache 금지)
 *  2. MOCK 모드: Redis cache `daily_pnl:{userId}:{kstDate}` 우선 조회 (TTL 자정까지)
 *  3. miss 시 `RiskPositionPort.sumDailyRealizedPnl` 위임 (Phase 2 어댑터 부재 시 0 fallback)
 *
 * spec 인용: ADR 0067 §5-5 + §7-1 DailyPnlCalculator.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class DailyPnlCalculator(
    private val redisTemplate: StringRedisTemplate,
    private val positionPortProvider: ObjectProvider<RiskPositionPort>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
) : DailyPnlProvider {
    private val log = LoggerFactory.getLogger(DailyPnlCalculator::class.java)

    companion object {
        private val KST = TimeZone.of("Asia/Seoul")
    }

    override fun getDailyPnl(
        userId: String,
        kstNow: Instant,
    ): BigDecimal {
        val kstDate = kstNow.toLocalDateTime(KST).date
        val key = RedisKeyRegistry.Risk.dailyPnl(userId, kstDate.toString())

        val realMode = RuntimeModeParser.isRealMode(magicjarMode)
        if (!realMode) {
            val cached = readCache(key)
            if (cached != null) return cached
        }

        val port = positionPortProvider.getIfAvailable()
        val pnl =
            if (port == null) {
                if (realMode) {
                    throw IllegalStateException("RiskPositionPort unavailable in REAL mode")
                }
                log.debug(
                    "DailyPnlCalculator RiskPositionPort 미합류 — fallback 0 (userId={} kstDate={})",
                    userId,
                    kstDate,
                )
                BigDecimal.ZERO
            } else {
                try {
                    val kstMidnight = kstMidnightInstant(kstDate)
                    port.sumDailyRealizedPnl(userId, kstMidnight)
                } catch (e: Exception) {
                    if (realMode) {
                        throw IllegalStateException("Daily realized PnL unavailable in REAL mode", e)
                    }
                    log.warn(
                        "DailyPnlCalculator port.sumDailyRealizedPnl 실패 (fallback 0): userId={} err={}",
                        userId,
                        e.message,
                    )
                    BigDecimal.ZERO
                }
            }

        if (!realMode) {
            writeCache(key, pnl, kstNow, kstDate)
        }
        return pnl
    }

    private fun readCache(key: String): BigDecimal? =
        try {
            redisTemplate.opsForValue().get(key)?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.warn("DailyPnlCalculator readCache 실패 (fail-open): key={} err={}", key, e.message)
            null
        }

    private fun writeCache(
        key: String,
        value: BigDecimal,
        kstNow: Instant,
        kstDate: LocalDate,
    ) {
        try {
            val nextMidnight = kstMidnightInstant(kstDate.plus(DatePeriod(days = 1)))
            val ttlSeconds = (nextMidnight.epochSeconds - kstNow.epochSeconds).coerceAtLeast(60L)
            redisTemplate.opsForValue().set(key, value.toPlainString(), Duration.ofSeconds(ttlSeconds))
        } catch (e: Exception) {
            log.warn("DailyPnlCalculator writeCache 실패 (skip): key={} err={}", key, e.message)
        }
    }

    private fun kstMidnightInstant(kstDate: LocalDate): Instant = kstDate.atTime(LocalTime(0, 0, 0)).toInstant(KST)
}
