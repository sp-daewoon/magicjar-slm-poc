package kr.spdaewoon.magicjar.domain.market.session

import kotlinx.datetime.Instant
import java.time.LocalDate

/**
 * 한국 주식시장 세션 sealed class (ADR 0013 §2 FSM 4 상태).
 * KST 기준 운영 시간 정합.
 *
 * 상태 전이 (영업일):
 *   자정~08:00 → Closed
 *   08:00~09:00 → PreMarket (시간외 단일가 장전)
 *   09:00~15:30 → Regular (정규장)
 *   15:30~15:40 → Closed (정규장 마감 ~ 장후 단일가 전 공백)
 *   15:40~16:00 → PostMarket (시간외 단일가 장후)
 *   16:00~자정  → Closed
 * 비영업일: 종일 Closed.
 */
sealed class MarketSession {
    /** 정규장 (09:00~15:30 KST) */
    object Regular : MarketSession()

    /** 시간외 단일가 — 장전 (08:00~09:00 KST, ADR 0013 §2-1) */
    object PreMarket : MarketSession()

    /** 시간외 단일가 — 장후 (15:40~16:00 KST, ADR 0013 §2-1) */
    object PostMarket : MarketSession()

    /** 휴장 (주말·공휴일·시간외) */
    data class Closed(
        val reason: String? = null,
    ) : MarketSession()
}

/**
 * 시장 세션 결정 포트 (ADR 0013 §2-2 정합).
 * 1차 구현: SimpleMarketSessionResolver (시간 기반 stub, PR-9b)
 * 본격 구현: KrxMarketSessionResolver (Redis + DB market_calendar + KIS chk-holiday, PR-29a)
 */
interface MarketSessionResolver {
    /** 특정 시각의 세션 결정 */
    fun resolve(instant: Instant): MarketSession

    /** 특정 시각이 속한 영업일 반환 — null = 비영업일 */
    fun businessDayOf(at: Instant): LocalDate?

    /** 날짜가 영업일인지 확인 */
    fun isBusinessDay(date: LocalDate): Boolean

    /** 다음 영업일 */
    fun nextBusinessDay(from: LocalDate): LocalDate

    /** 이전 영업일 */
    fun previousBusinessDay(from: LocalDate): LocalDate

    /** 편의 메서드 — 정규장 열림 여부 */
    fun isOpen(instant: Instant): Boolean = resolve(instant) is MarketSession.Regular
}
