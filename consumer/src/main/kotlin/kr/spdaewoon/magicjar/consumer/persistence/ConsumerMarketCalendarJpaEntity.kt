package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * market_calendar JPA 엔티티 — consumer 모듈 자체 사본 (api 모듈과 코드 중복 OK — 모듈 분리 정합).
 *
 * 본 사본은 read-only 용도:
 *   - BusinessDayCalendar 구현체(ConsumerBusinessDayCalendar)에서 영업일 lookup
 *   - market_calendar 테이블 자체는 api 모듈 Flyway V6 + MarketCalendarSeedRunner(batch)에서 관리
 *   - consumer는 시드/sync 책임 0 — 단순 isBusinessDay 조회만
 *
 * ADR 0013 §2-2 + Flyway V6 정합.
 */
@Entity
@Table(name = "market_calendar")
class ConsumerMarketCalendarJpaEntity(
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
    protected constructor() : this(date = LocalDate.EPOCH, isBusinessDay = false, source = "")
}
