package kr.spdaewoon.magicjar.batch.scheduler

import kr.spdaewoon.magicjar.batch.job.market.MarketIndexDailyCloseJob
import kr.spdaewoon.magicjar.batch.job.market.MarketIndexSyncJob
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T-MARKET cron scheduler (ADR 0100 §2-4 LOCK).
 *
 * ### cron 매트릭스
 *   - 1분 cron (09:00~15:30 KST · MON-FRI): MarketIndexSyncJob.run("minute")
 *     · market_calendar 영업일 + 정규장 시간 게이트
 *   - 16:30 KST (MON-FRI): MarketIndexDailyCloseJob.runDomestic("16:30")
 *   - 06:00 KST (TUE-SAT · 미국 정규장 마감 후): MarketIndexDailyCloseJob.runOverseas("06:00")
 *
 * ### 영업일 게이트
 * - cron `zone=Asia/Seoul` MON-FRI 1차 게이트
 * - market_calendar DB 기반 BusinessDayCalendar 로 공휴일·임시휴장 skip
 * - 해외 일봉(06:00 KST)은 KRX 캘린더가 아닌 미국 캘린더 영역이므로 여기서는 막지 않음
 *
 * ### sync-at-boot 패턴
 * - `magicjar.market.index.sync.boot=true` 시 부팅 직후 1회 (dev smoke 용도)
 */
@Component
@EnableScheduling
class MarketIndexScheduler(
    private val marketIndexSyncJob: MarketIndexSyncJob,
    private val marketIndexDailyCloseJob: MarketIndexDailyCloseJob,
    private val businessDayCalendar: BusinessDayCalendar,
    @Value("\${magicjar.market.index.sync.boot:false}")
    private val syncAtBoot: Boolean,
) {
    private val log = LoggerFactory.getLogger(MarketIndexScheduler::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 1분 cron — 09:00~15:30 KST MON-FRI 매분 발사.
     *
     * cron expression 분해 (KST):
     *   "0 * 9-15 * * MON-FRI"
     *   · 분초: every minute 0초 발사
     *   · 시  : 9·10·11·12·13·14·15 시 (15시는 30분 cutoff 별도 in-Job check)
     *   · 요일: 평일만
     */
    @Scheduled(cron = "0 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    fun syncEveryMinute() {
        val now = LocalDateTime.now(kst)
        if (!isKrxBusinessDay(now.toLocalDate(), "minute", noisy = false)) {
            return
        }
        // 15:30 KST 이후는 정규장 외 → skip (15:00~15:30 한정)
        if (now.hour == 15 && now.minute > 30) {
            return
        }
        try {
            marketIndexSyncJob.run("minute")
        } catch (e: Exception) {
            log.warn("[MarketIndexScheduler] minute 발사 실패 (다음 분 정상 재시도) err={}", e.message)
        }
    }

    /**
     * 16:30 KST 국내 일봉 backfill — 정규장 마감 + 30분 buffer.
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Seoul")
    fun dailyDomesticClose() {
        if (!isKrxBusinessDay(LocalDate.now(kst), "16:30", noisy = true)) {
            return
        }
        try {
            marketIndexDailyCloseJob.runDomestic("16:30")
        } catch (e: Exception) {
            log.warn("[MarketIndexScheduler] domestic daily 발사 실패 err={}", e.message)
        }
    }

    /**
     * 06:00 KST 해외 일봉 backfill — TUE-SAT (월요일 아침 = 미국 일요일, 휴장).
     *
     * KIS 응답 부재 시 graceful skip (Job 내부 처리).
     */
    @Scheduled(cron = "0 0 6 * * TUE-SAT", zone = "Asia/Seoul")
    fun dailyOverseasClose() {
        try {
            marketIndexDailyCloseJob.runOverseas("06:00")
        } catch (e: Exception) {
            log.warn("[MarketIndexScheduler] overseas daily 발사 실패 err={}", e.message)
        }
    }

    private fun isKrxBusinessDay(
        date: LocalDate,
        cronTag: String,
        noisy: Boolean,
    ): Boolean {
        if (businessDayCalendar.isBusinessDay(date)) return true
        if (noisy) {
            log.info("[MarketIndexScheduler] cron={} skip — KRX 휴장일 ({})", cronTag, date)
        } else {
            log.debug("[MarketIndexScheduler] cron={} skip — KRX 휴장일 ({})", cronTag, date)
        }
        return false
    }
}
