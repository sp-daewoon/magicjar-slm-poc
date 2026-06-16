package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * market_calendar Spring Data JPA 리포지터리 — consumer 모듈 (read-only).
 *
 * ADR 0051 정합: body 있는 fun 0건 — query inference + 기본 findById 만 사용.
 *
 * 책임:
 *   - ConsumerBusinessDayCalendar 의 isBusinessDay() 조회 baseline
 *   - 시드/sync는 api 모듈 + batch 모듈 책임 (consumer는 read-only LOCK)
 */
interface ConsumerMarketCalendarJpaRepository : JpaRepository<ConsumerMarketCalendarJpaEntity, LocalDate> {
    fun findByDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<ConsumerMarketCalendarJpaEntity>
}
