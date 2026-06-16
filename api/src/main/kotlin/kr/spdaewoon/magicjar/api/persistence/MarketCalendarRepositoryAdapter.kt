package kr.spdaewoon.magicjar.api.persistence

import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import kr.spdaewoon.magicjar.domain.market.session.port.MarketCalendarRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * MarketCalendarRepository 포트 구현 (api 모듈 JPA 어댑터).
 * ADR 0013 §2-2 + Flyway V6 market_calendar 정합.
 */
@Component
class MarketCalendarRepositoryAdapter(
    private val jpaRepository: MarketCalendarJpaRepository,
) : MarketCalendarRepository {
    private val log = LoggerFactory.getLogger(MarketCalendarRepositoryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findByDate(date: LocalDate): MarketCalendarEntry? = jpaRepository.findById(date).map { it.toDomain() }.orElse(null)

    @Transactional(readOnly = true)
    override fun findByDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarEntry> = jpaRepository.findByDateBetween(from, to).map { it.toDomain() }

    @Transactional
    override fun upsert(entry: MarketCalendarEntry): MarketCalendarEntry {
        jpaRepository.upsert(
            date = entry.date,
            isBusinessDay = entry.isBusinessDay,
            isHoliday = entry.isHoliday,
            holidayName = entry.holidayName,
            source = entry.source,
        )
        return entry
    }

    @Transactional
    override fun upsertAll(entries: List<MarketCalendarEntry>): Int {
        if (entries.isEmpty()) return 0
        var count = 0
        for (entry in entries) {
            count +=
                jpaRepository.upsert(
                    date = entry.date,
                    isBusinessDay = entry.isBusinessDay,
                    isHoliday = entry.isHoliday,
                    holidayName = entry.holidayName,
                    source = entry.source,
                )
        }
        log.debug("market_calendar upsertAll: total={} affected={}", entries.size, count)
        return count
    }

    @Transactional(readOnly = true)
    override fun count(): Long = jpaRepository.count()
}
