package kr.spdaewoon.magicjar.domain.market.session.port

import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import java.time.LocalDate

/**
 * market_calendar 영속 포트 (ADR 0013 §2-2 + Flyway V6 정합).
 * 구현은 api 모듈 MarketCalendarRepositoryAdapter.
 *
 * ADR 0051 정합: JpaRepository default fun body 금지 — 헬퍼는 adapter 또는 extension fun으로.
 */
interface MarketCalendarRepository {
    /** 날짜 단건 조회 — null = 캘린더 미등록 */
    fun findByDate(date: LocalDate): MarketCalendarEntry?

    /** 날짜 범위 조회 (inclusive) */
    fun findByDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarEntry>

    /** upsert (멱등 — KIS 응답·시드 중복 호출 허용) */
    fun upsert(entry: MarketCalendarEntry): MarketCalendarEntry

    /** 배치 upsert */
    fun upsertAll(entries: List<MarketCalendarEntry>): Int

    /** 등록된 총 row 수 (SeedRunner: 0건 시 자동 시드) */
    fun count(): Long
}
