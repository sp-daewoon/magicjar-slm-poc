package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketCalendarJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.market.session.MarketCalendarEntry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * BusinessDayCalendar 도메인 포트 구현체 — consumer 모듈 (ADR 0013 §2-2 + Flyway V6 정합).
 *
 * T-CC Phase 2-A (ADR 0086 §2-3) 정합:
 *   - CandleGapDetectorAdapter 가 expected sequence 생성 시 의무 호출
 *   - 햇징 룰 #6 (false positive 가드) baseline — 휴장일 갭 검출 제외
 *
 * 동작:
 *   1차 lookup: market_calendar DB row (api 모듈 MarketCalendarSeedRunner + batch sync job 시드)
 *   2차 fallback: row 미등록 시 주말 가드만 (DayOfWeek.SATURDAY/SUNDAY 제외)
 *
 * 사유:
 *   - 운영 환경 market_calendar 미시드 상태에서도 안전 동작 (consumer 단독 운영 가능)
 *   - chart-curator skill §함정 가드 정합 — false positive 방지 보다 false negative 회피 우선
 *     (휴장일 갭으로 KIS 무의미 호출 발생 시 audit + Slack notice로 운영자 확인 가능)
 *
 * seedRange는 consumer 영역에서 구현 0 — read-only LOCK (api·batch가 시드 책임).
 */
@Component
class ConsumerBusinessDayCalendar(
    private val marketCalendarJpaRepository: ConsumerMarketCalendarJpaRepository,
) : BusinessDayCalendar {
    private val log = LoggerFactory.getLogger(ConsumerBusinessDayCalendar::class.java)

    @Transactional(readOnly = true)
    override fun isBusinessDay(date: LocalDate): Boolean {
        val entry = marketCalendarJpaRepository.findById(date).orElse(null)
        if (entry != null) {
            return entry.isBusinessDay
        }
        // fallback — row 미등록 시 주말 가드만 (햇징 룰 #6 false positive 방지 의무 — chart-curator skill §함정 가드)
        val dow = date.dayOfWeek
        val weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
        if (log.isTraceEnabled) {
            log.trace(
                "BusinessDayCalendar fallback (market_calendar row 0): date={} dow={} weekday={}",
                date,
                dow,
                weekday,
            )
        }
        return weekday
    }

    /**
     * consumer 영역은 read-only LOCK — seedRange 직접 호출 0건 (api·batch가 시드 책임).
     * 호출 시 IllegalStateException — 영역 침범 가드.
     */
    override fun seedRange(
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCalendarEntry> =
        throw IllegalStateException(
            "ConsumerBusinessDayCalendar.seedRange() 미지원 — consumer read-only LOCK " +
                "(ADR 0085 영역 분리, 시드는 api MarketCalendarSeedRunner + batch MarketCalendarSyncJob 책임)",
        )
}
