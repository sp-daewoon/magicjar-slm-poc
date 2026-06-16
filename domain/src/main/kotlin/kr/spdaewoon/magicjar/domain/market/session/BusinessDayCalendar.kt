package kr.spdaewoon.magicjar.domain.market.session

import java.time.LocalDate

/**
 * 영업일 캘린더 도메인 포트 (ADR 0013 §2-2).
 * 1차 소스: market_calendar DB + 정적 시드
 * 2차 소스: KIS chk-holiday(CTCA0903R) cron 동기화
 */
interface BusinessDayCalendar {
    fun isBusinessDay(date: LocalDate): Boolean

    fun seedRange(
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarEntry>
}
