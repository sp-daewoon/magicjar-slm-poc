package kr.spdaewoon.magicjar.domain.market.session

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.LocalDate

/**
 * 정규장 게이트 단일 판정 본체 (라이브 시그널 + 시나리오 리플레이 공용).
 *
 * 라이브 [kr.spdaewoon.magicjar.consumer.signal.RealtimeSignalGenerator] 의
 * `isWithinTradingHours` 로직을 추출해 도메인 순수 함수로 만든 것이다. 라이브와 리플레이가
 * **같은 게이트 코드**를 호출하도록 강제하기 위함이다 (ADR 0066 정규장 게이트 정합 + readiness P1).
 *
 * ### 판정 규칙 (라이브 동작과 1:1 동일)
 * - 정규장 판정: [MarketSessionResolver.resolve] 가 [MarketSession.Regular] 반환 시.
 * - 영업일 판정: [BusinessDayCalendar] (market_calendar DB 권위) 우선,
 *   bean 부재 또는 조회 예외 시 [MarketSessionResolver.businessDayOf] (주말 가드) fallback.
 *
 * ### 결정론
 * 외부 I/O 0건(주입된 resolver·calendar 호출 외) — 동일 입력에 동일 출력.
 * 리플레이는 ADR 0107 sandbox 분리 LOCK 하에 이 도메인 게이트만 재사용하며 실주문 경로를 접촉하지 않는다.
 */
class TradingHoursGate(
    private val sessionResolver: MarketSessionResolver,
    private val businessDayCalendar: BusinessDayCalendar? = null,
    private val zone: TimeZone = SEOUL,
    private val onCalendarError: (LocalDate, Throwable) -> Unit = { _, _ -> },
) {
    /**
     * [boundaryTs] (캔들 마감 UTC Instant) 가 영업일 + 정규장(09:00~15:30 KST) 안인지 판정.
     *
     * @return true = 평가/시그널 진행 가능, false = 게이트 skip 대상(장외·휴장).
     */
    fun isWithinTradingHours(boundaryTs: Instant): Boolean {
        val session = sessionResolver.resolve(boundaryTs)
        if (session !is MarketSession.Regular) return false

        val calendar = businessDayCalendar
        val kstDate = boundaryTs.toLocalDateTime(zone).date.toJavaLocalDate()
        return if (calendar != null) {
            try {
                calendar.isBusinessDay(kstDate)
            } catch (e: Exception) {
                onCalendarError(kstDate, e)
                sessionResolver.businessDayOf(boundaryTs) != null
            }
        } else {
            sessionResolver.businessDayOf(boundaryTs) != null
        }
    }

    companion object {
        private val SEOUL = TimeZone.of("Asia/Seoul")

        private fun kotlinx.datetime.LocalDate.toJavaLocalDate(): LocalDate = LocalDate.of(year, monthNumber, dayOfMonth)
    }
}
