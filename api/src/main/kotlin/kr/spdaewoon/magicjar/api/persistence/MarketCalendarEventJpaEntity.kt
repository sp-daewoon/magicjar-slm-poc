package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * 사용자 등록 시장 일정 엔티티.
 *
 * market_calendar 는 KRX 영업/휴장 기준표로 유지하고, IPO/실적/거시 이벤트 등은 본 테이블에서 확장한다.
 */
@Entity
@Table(name = "market_calendar_event")
class MarketCalendarEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false, updatable = false)
    val userId: String,
    @Column(name = "event_date", nullable = false)
    var eventDate: LocalDate,
    @Column(name = "market", length = 16, nullable = false)
    var market: String = "KRX",
    @Column(name = "event_type", length = 32, nullable = false)
    var eventType: String,
    @Column(name = "title", length = 120, nullable = false)
    var title: String,
    @Column(name = "description", columnDefinition = "text")
    var description: String? = null,
    @Column(name = "instrument_code", length = 16)
    var instrumentCode: String? = null,
    @Column(name = "source", length = 32, nullable = false)
    var source: String = "MANUAL",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = "",
        eventDate = LocalDate.EPOCH,
        eventType = "",
        title = "",
    )
}
