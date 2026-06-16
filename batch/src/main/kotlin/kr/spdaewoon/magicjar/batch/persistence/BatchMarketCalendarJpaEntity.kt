package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import java.time.Instant
import java.time.LocalDate

/**
 * market_calendar JPA 엔티티 — batch 모듈 자체 사본 (api 모듈과 테이블 공유).
 * Flyway V6 정합 (api 모듈 단독 마이그레이션 — batch는 flyway.enabled=false).
 */
@Entity
@Table(name = "market_calendar")
class BatchMarketCalendarJpaEntity(
    @Id
    @Column(name = "date", nullable = false)
    val date: LocalDate,
    @Column(name = "is_business_day", nullable = false)
    val isBusinessDay: Boolean,
    @Column(name = "is_holiday", nullable = false)
    val isHoliday: Boolean = false,
    @Column(name = "holiday_name", length = 64)
    val holidayName: String? = null,
    @Column(name = "source", length = 32, nullable = false)
    val source: String,
    @Column(name = "synced_at", nullable = false)
    val syncedAt: Instant = Instant.now(),
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(): MarketCalendarEntry =
        MarketCalendarEntry(
            date = date,
            isBusinessDay = isBusinessDay,
            isHoliday = isHoliday,
            holidayName = holidayName,
            source = source,
        )
}
