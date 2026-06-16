package kr.spdaewoon.magicjar.api.market.session

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.market.session.port.MarketCalendarRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * KRX 영업일 캘린더 기반 MarketSessionResolver 본격 구현 (ADR 0013 §2-2, PR-29a).
 *
 * 세션 결정 우선순위:
 *   1. Redis `session:cache:current` TTL 60초 캐시 (히트 시 즉시 반환)
 *   2. market_calendar DB 영업일 조회
 *   3. 시간 기반 FSM (ADR 0013 §2-1)
 *
 * KST 시간 전이 (ADR 0013 §2-1):
 *   자정~08:00    → Closed
 *   08:00~09:00   → PreMarket (시간외 단일가 장전)
 *   09:00~15:30   → Regular   (정규장)
 *   15:30~15:40   → Closed    (정규장 ~ 장후 공백)
 *   15:40~16:00   → PostMarket (시간외 단일가 장후)
 *   16:00~자정    → Closed
 *
 * SimpleMarketSessionResolver(PR-9b) 대체 본격 구현.
 * consumer 모듈은 이 Bean을 import해 사용 (MarketSessionResolver 포트 타입).
 */
@Component
class KrxMarketSessionResolver(
    private val marketCalendarRepository: MarketCalendarRepository,
    private val redisTemplate: StringRedisTemplate,
) : MarketSessionResolver {
    private val log = LoggerFactory.getLogger(KrxMarketSessionResolver::class.java)

    companion object {
        private val SESSION_CACHE_KEY = RedisKeyRegistry.Market.sessionCurrent()
        private val SESSION_CACHE_TTL = Duration.ofSeconds(60)
        private val KST = TimeZone.of("Asia/Seoul")
        private val KST_ZONE = ZoneId.of("Asia/Seoul")
    }

    override fun resolve(instant: Instant): MarketSession {
        // 1. Redis 캐시 확인 (TTL 60초)
        val cached = redisTemplate.opsForValue().get(SESSION_CACHE_KEY)
        if (cached != null) {
            val session = parseSessionFromCache(cached)
            if (session != null) return session
        }

        // 2. market_calendar + 시간 기반 FSM으로 결정
        val session = determineSession(instant)

        // 3. Redis 캐시 갱신
        redisTemplate.opsForValue().set(SESSION_CACHE_KEY, sessionToString(session), SESSION_CACHE_TTL)

        return session
    }

    private fun determineSession(instant: Instant): MarketSession {
        val kstDateTime = instant.toLocalDateTime(KST)
        val date = LocalDate.of(kstDateTime.year, kstDateTime.monthNumber, kstDateTime.dayOfMonth)

        // market_calendar DB 조회 — 비영업일이면 Closed
        val calendarEntry = marketCalendarRepository.findByDate(date)
        if (calendarEntry != null && !calendarEntry.isBusinessDay) {
            val reason = if (calendarEntry.isHoliday) "공휴일: ${calendarEntry.holidayName ?: "공휴일"}" else "비영업일"
            log.debug("KRX 비영업일 → Closed: date={} reason={}", date, reason)
            return MarketSession.Closed(reason = reason)
        }

        // 주말 fallback (market_calendar 미등록 시)
        val dayOfWeek = kstDateTime.dayOfWeek
        if (dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY ||
            dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
        ) {
            return MarketSession.Closed(reason = "주말")
        }

        // 시간 기반 FSM (ADR 0013 §2-1)
        val timeInt = kstDateTime.hour * 100 + kstDateTime.minute

        return when {
            timeInt in 900..1529 -> MarketSession.Regular // 09:00~15:29 정규장
            timeInt in 800..859 -> MarketSession.PreMarket // 08:00~08:59 장전 시간외
            timeInt in 1540..1559 -> MarketSession.PostMarket // 15:40~15:59 장후 시간외
            else -> MarketSession.Closed(reason = "장외시간")
        }
    }

    override fun businessDayOf(at: Instant): LocalDate? {
        val kstDateTime = at.toLocalDateTime(KST)
        val date = LocalDate.of(kstDateTime.year, kstDateTime.monthNumber, kstDateTime.dayOfMonth)
        return if (isBusinessDay(date)) date else null
    }

    override fun isBusinessDay(date: LocalDate): Boolean {
        val entry = marketCalendarRepository.findByDate(date)
        if (entry != null) return entry.isBusinessDay

        // DB 미등록 시 주말 fallback
        val dayOfWeek = date.dayOfWeek
        return dayOfWeek != java.time.DayOfWeek.SATURDAY &&
            dayOfWeek != java.time.DayOfWeek.SUNDAY
    }

    override fun nextBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.plusDays(1)
        // 최대 14일 탐색 (연속 공휴일 대비)
        repeat(14) {
            if (isBusinessDay(candidate)) return candidate
            candidate = candidate.plusDays(1)
        }
        log.warn("nextBusinessDay: 14일 내 영업일 미발견, from={}", from)
        return candidate
    }

    override fun previousBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.minusDays(1)
        repeat(14) {
            if (isBusinessDay(candidate)) return candidate
            candidate = candidate.minusDays(1)
        }
        log.warn("previousBusinessDay: 14일 내 영업일 미발견, from={}", from)
        return candidate
    }

    // ── 캐시 직렬화 헬퍼 ─────────────────────────────────

    private fun sessionToString(session: MarketSession): String =
        when (session) {
            is MarketSession.Regular -> "REGULAR"
            is MarketSession.PreMarket -> "PRE_MARKET"
            is MarketSession.PostMarket -> "POST_MARKET"
            is MarketSession.Closed -> "CLOSED:${session.reason ?: ""}"
        }

    private fun parseSessionFromCache(value: String): MarketSession? =
        when {
            value == "REGULAR" -> MarketSession.Regular
            value == "PRE_MARKET" -> MarketSession.PreMarket
            value == "POST_MARKET" -> MarketSession.PostMarket
            value.startsWith("CLOSED:") -> MarketSession.Closed(reason = value.removePrefix("CLOSED:").ifEmpty { null })
            else -> null
        }
}
