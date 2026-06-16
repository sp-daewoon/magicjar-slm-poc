package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * market_calendar Spring Data JPA Repository — batch 모듈.
 * ADR 0051 정합: JpaRepository body 있는 fun 금지.
 * 동일 물리 테이블 — api 모듈 MarketCalendarJpaRepository와 공유.
 */
interface BatchMarketCalendarJpaRepository : JpaRepository<BatchMarketCalendarJpaEntity, LocalDate> {
    fun findByDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<BatchMarketCalendarJpaEntity>

    /**
     * upsert — date PK 충돌 시 갱신.
     * ADR 0051: body 있는 fun은 @Query native 쿼리로 격리.
     *
     * 함정 (v3.1.0 hotfix 사례): ApplicationRunner 등 트랜잭션 컨텍스트
     * 부재 환경에서 호출 시 "Executing an update/delete query" 예외.
     * @Transactional 의무 — Modifying 쿼리는 트랜잭션 안에서 실행.
     */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO market_calendar (date, is_business_day, is_holiday, holiday_name, source, synced_at, created_at)
            VALUES (:date, :isBusinessDay, :isHoliday, :holidayName, :source, now(), now())
            ON CONFLICT (date) DO UPDATE SET
                is_business_day = EXCLUDED.is_business_day,
                is_holiday       = EXCLUDED.is_holiday,
                holiday_name     = COALESCE(EXCLUDED.holiday_name, market_calendar.holiday_name),
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
