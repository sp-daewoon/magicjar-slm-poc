package kr.spdaewoon.magicjar.consumer.infrastructure.session

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 시장 세션 결정 어댑터 — 시간 기반 stub (PR-9b 1차 구현).
 *
 * PR-29a: KrxMarketSessionResolver 본격 구현 (api 모듈)로 대체됨.
 * consumer 모듈이 api 모듈에 의존하지 않는 구조상, consumer 내부에서는 이 bean이 fallback.
 * api 모듈의 KrxMarketSessionResolver와 MarketSessionResolver 포트 공유.
 *
 * @ConditionalOnMissingBean: KrxMarketSessionResolver bean이 존재하면 이 bean 비활성.
 * consumer 모듈은 이 bean을 DailyCloseFinalizer·TickFallbackEmitter에서 사용.
 *
 * KIS-first raw 검증 (PR-9b 주석 유지):
 *   KIS open-trading-api chk-holiday endpoint(CTCA0903R) 는
 *   KrxMarketSessionResolver(api)가 담당 — consumer stub은 시간 기반만.
 *
 * 신규 메서드 (MarketSessionResolver 인터페이스 PR-29a 확장):
 *   - businessDayOf, isBusinessDay, nextBusinessDay, previousBusinessDay
 *   주말만 처리 (공휴일 DB 연동 없음 — stub 수준).
 */
@Component
@ConditionalOnMissingBean(name = ["krxMarketSessionResolver"])
class SimpleMarketSessionResolver : MarketSessionResolver {
    private val log = LoggerFactory.getLogger(SimpleMarketSessionResolver::class.java)

    private val kst = TimeZone.of("Asia/Seoul")
    private val kstZone = java.time.ZoneId.of("Asia/Seoul")

    override fun resolve(instant: Instant): MarketSession {
        val kstDateTime = instant.toLocalDateTime(kst)
        val dayOfWeek = kstDateTime.dayOfWeek
        val hour = kstDateTime.hour
        val minute = kstDateTime.minute

        // 주말 → Closed
        if (dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY ||
            dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
        ) {
            return MarketSession.Closed(reason = "주말")
        }

        val timeInt = hour * 100 + minute

        return when {
            timeInt in 900..1529 -> MarketSession.Regular
            timeInt in 800..859 -> MarketSession.PreMarket
            timeInt in 1540..1559 -> MarketSession.PostMarket
            else -> MarketSession.Closed(reason = "장외시간")
        }
    }

    override fun businessDayOf(at: Instant): LocalDate? {
        val kstDateTime = at.toLocalDateTime(kst)
        val date =
            LocalDate.of(
                kstDateTime.year,
                kstDateTime.monthNumber,
                kstDateTime.dayOfMonth,
            )
        return if (isBusinessDay(date)) date else null
    }

    override fun isBusinessDay(date: LocalDate): Boolean {
        val dayOfWeek = date.dayOfWeek
        // stub: 주말만 제외 (공휴일 미인지)
        return dayOfWeek != java.time.DayOfWeek.SATURDAY &&
            dayOfWeek != java.time.DayOfWeek.SUNDAY
    }

    override fun nextBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.plusDays(1)
        repeat(14) {
            if (isBusinessDay(candidate)) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    override fun previousBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.minusDays(1)
        repeat(14) {
            if (isBusinessDay(candidate)) return candidate
            candidate = candidate.minusDays(1)
        }
        return candidate
    }
}
