package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import java.time.Instant
import java.time.LocalDate

/**
 * market_calendar JPA 엔티티 (Flyway V6 정합 — ADR 0013 §2-2).
 * no-arg 플러그인 대상 — protected 생성자 불필요 (kotlin-jpa plugin 처리).
 */
@Entity
@Table(name = "market_calendar")
class MarketCalendarJpaEntity(
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
    val source: String, // 'STATIC_SEED' | 'KIS_CHK_HOLIDAY' | 'MANUAL'
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

    companion object {
        fun fromDomain(
            entry: MarketCalendarEntry,
            now: Instant = Instant.now(),
        ): MarketCalendarJpaEntity =
            MarketCalendarJpaEntity(
                date = entry.date,
                isBusinessDay = entry.isBusinessDay,
                isHoliday = entry.isHoliday,
                holidayName = entry.holidayName,
                source = entry.source,
                syncedAt = now,
                createdAt = now,
            )
    }
}
