package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.Optional

interface MarketCalendarEventJpaRepository : JpaRepository<MarketCalendarEventJpaEntity, Long> {
    fun findByUserIdAndEventDateBetweenOrderByEventDateAscCreatedAtAsc(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarEventJpaEntity>

    fun findByIdAndUserId(
        id: Long,
        userId: String,
    ): Optional<MarketCalendarEventJpaEntity>
}
