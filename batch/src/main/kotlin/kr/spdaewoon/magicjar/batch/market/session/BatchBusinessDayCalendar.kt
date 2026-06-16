package kr.spdaewoon.magicjar.batch.market.session

import kr.spdaewoon.magicjar.batch.persistence.BatchMarketCalendarJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * batch 모듈 영업일 캘린더.
 *
 * 1차: market_calendar DB row, 2차: 주말 fallback.
 * 휴장일 row가 있으면 weekday라도 반드시 비영업일로 본다.
 */
@Component
class BatchBusinessDayCalendar(
    private val marketCalendarJpaRepository: BatchMarketCalendarJpaRepository,
) : BusinessDayCalendar {
    private val log = LoggerFactory.getLogger(BatchBusinessDayCalendar::class.java)

    override fun isBusinessDay(date: LocalDate): Boolean {
        val entry = marketCalendarJpaRepository.findById(date).orElse(null)
        if (entry != null) return entry.isBusinessDay

        val weekday = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
        if (!weekday) return false

        log.debug("BatchBusinessDayCalendar fallback weekday=true: date={}", date)
        return true
    }

    override fun seedRange(
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarEntry> =
        marketCalendarJpaRepository
            .findByDateBetween(from, to)
            .map { it.toDomain() }
}
