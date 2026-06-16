package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * market_calendar Spring Data JPA 리포지터리.
 * ADR 0051 정합: JpaRepository body 있는 fun 금지.
 * 헬퍼 로직은 MarketCalendarRepositoryAdapter에서 처리.
 */
interface MarketCalendarJpaRepository : JpaRepository<MarketCalendarJpaEntity, LocalDate> {
    fun findByDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarJpaEntity>

    /**
     * upsert — date PK 충돌 시 is_business_day·is_holiday·holiday_name·source·synced_at 갱신.
     * ADR 0051 정합: body가 있는 fun은 @Query native 쿼리로 격리.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO market_calendar (date, is_business_day, is_holiday, holiday_name, source, synced_at, created_at)
            VALUES (:date, :isBusinessDay, :isHoliday, :holidayName, :source, now(), now())
            ON CONFLICT (date) DO UPDATE SET
                is_business_day = EXCLUDED.is_business_day,
                is_holiday       = EXCLUDED.is_holiday,
                holiday_name     = EXCLUDED.holiday_name,
                source           = EXCLUDED.source,
                synced_at        = now()
        """,
    )
    fun upsert(
        @Param("date") date: LocalDate,
        @Param("isBusinessDay") isBusinessDay: Boolean,
        @Param("isHoliday") isHoliday: Boolean,
        @Param("holidayName") holidayName: String?,
        @Param("source") source: String,
    ): Int
}
