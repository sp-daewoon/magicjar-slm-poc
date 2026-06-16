package kr.spdaewoon.magicjar.domain.market.session

import java.time.LocalDate

/**
 * 영업일 캘린더 항목 (ADR 0013 §2-2 + Flyway V6 market_calendar 1:1 정합).
 *
 * source 값:
 *   "STATIC_SEED"    — JSON 정적 시드
 *   "KIS_CHK_HOLIDAY" — KIS chk-holiday(CTCA0903R) 응답
 *   "MANUAL"         — 수동 입력
 */
data class MarketCalendarEntry(
    val date: LocalDate,
    val isBusinessDay: Boolean,
    val isHoliday: Boolean,
    val holidayName: String?,
    val source: String, // "STATIC_SEED" | "KIS_CHK_HOLIDAY" | "MANUAL"
)
