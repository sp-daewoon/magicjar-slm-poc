package kr.spdaewoon.magicjar.domain.market.session

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.LocalDate

/**
 * 임의 시각(과거 boundary 포함)에 대해 결정론적으로 세션을 판정하는 순수 resolver.
 *
 * ### 왜 필요한가 (readiness P1 — 리플레이-라이브 게이트 정합)
 * 라이브 [KrxMarketSessionResolver]는 Redis `session:cache:current` 단일 키로 "지금" 세션을 캐싱한다.
 * 이는 항상 현재 시각을 평가하는 라이브에서는 옳지만, 과거 boundary 타임스탬프를 평가하는
 * 시나리오 리플레이에 그대로 쓰면 "현재 세션"을 과거 boundary에 잘못 적용한다.
 *
 * 이 resolver는 동일한 KST 시간 기반 FSM(ADR 0013 §2-1)을 Redis 없이 적용하고,
 * 영업일 판정은 주입된 [isBusinessDay] 술어(예: market_calendar 조회)에 위임한다.
 * 따라서 라이브와 동일한 정규장 규칙을 유지하면서도 과거 시각을 정확히 판정한다.
 *
 * ### FSM (KrxMarketSessionResolver.determineSession 과 1:1 동일)
 *   09:00~15:29 → Regular · 08:00~08:59 → PreMarket · 15:40~15:59 → PostMarket · 그 외 → Closed
 *   비영업일 → 종일 Closed.
 */
class PerInstantMarketSessionResolver(
    private val isBusinessDay: (LocalDate) -> Boolean,
    private val zone: TimeZone = SEOUL,
) : MarketSessionResolver {
    override fun resolve(instant: Instant): MarketSession {
        val kstDateTime = instant.toLocalDateTime(zone)
        val date = LocalDate.of(kstDateTime.year, kstDateTime.monthNumber, kstDateTime.dayOfMonth)
        if (!isBusinessDay(date)) return MarketSession.Closed(reason = "비영업일")

        val timeInt = kstDateTime.hour * 100 + kstDateTime.minute
        return when (timeInt) {
            in 900..1529 -> MarketSession.Regular
            in 800..859 -> MarketSession.PreMarket
            in 1540..1559 -> MarketSession.PostMarket
            else -> MarketSession.Closed(reason = "장외시간")
        }
    }

    override fun businessDayOf(at: Instant): LocalDate? {
        val kstDateTime = at.toLocalDateTime(zone)
        val date = LocalDate.of(kstDateTime.year, kstDateTime.monthNumber, kstDateTime.dayOfMonth)
        return if (isBusinessDay(date)) date else null
    }

    override fun isBusinessDay(date: LocalDate): Boolean = isBusinessDay.invoke(date)

    override fun nextBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.plusDays(1)
        repeat(MAX_LOOKUP_DAYS) {
            if (isBusinessDay(candidate)) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    override fun previousBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.minusDays(1)
        repeat(MAX_LOOKUP_DAYS) {
            if (isBusinessDay(candidate)) return candidate
            candidate = candidate.minusDays(1)
        }
        return candidate
    }

    companion object {
        private val SEOUL = TimeZone.of("Asia/Seoul")
        private const val MAX_LOOKUP_DAYS = 14
    }
}
