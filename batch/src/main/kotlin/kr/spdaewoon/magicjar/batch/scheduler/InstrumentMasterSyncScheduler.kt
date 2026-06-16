package kr.spdaewoon.magicjar.batch.scheduler

import kr.spdaewoon.magicjar.batch.job.instrument.InstrumentSyncJob
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 종목 마스터 sync 스케줄러 (ADR 0039 §2-1 — 13 cron 시각 LOCK).
 *
 * cron 시각 (KST — ADR 0039 §2-1 정합):
 *   06:05 · 07:00 · 07:40 · 08:00 · 08:50 · 09:51 · 11:00
 *   17:15 · 17:35 · 18:00 · 18:15 · 18:35 · 19:00
 *
 * 영업일 게이트 (ADR 0039 §2-2):
 *   - cron zone=Asia/Seoul으로 MON-FRI 1차 필터
 *   - 추가 BusinessDayCalendar 호출 — market_calendar 휴장일 row 우선
 *
 * MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true 시 부팅 직후 1회 즉시 sync (dev 기본 true 권장).
 */
@Component
@EnableScheduling
class InstrumentMasterSyncScheduler(
    private val instrumentSyncJob: InstrumentSyncJob,
    private val businessDayCalendar: BusinessDayCalendar,
    @Value("\${magicjar.instrument.sync-at-boot:false}")
    private val syncAtBoot: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(InstrumentMasterSyncScheduler::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    // ─── 부팅 직후 sync (ApplicationRunner) ─────────────────────────────

    override fun run(args: ApplicationArguments) {
        if (syncAtBoot) {
            log.info("[instrument-sync] 부팅 직후 sync 시작 (magicjar.instrument.sync-at-boot=true)")
            runIfBusinessDay("boot")
        }
    }

    // ─── 13 cron 시각 (ADR 0039 §2-1 LOCK) ──────────────────────────────

    @Scheduled(cron = "0 5 6 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt0605() = runIfBusinessDay("06:05")

    @Scheduled(cron = "0 0 7 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt0700() = runIfBusinessDay("07:00")

    @Scheduled(cron = "0 40 7 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt0740() = runIfBusinessDay("07:40")

    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt0800() = runIfBusinessDay("08:00")

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt0850() = runIfBusinessDay("08:50")

    @Scheduled(cron = "0 51 9 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt0951() = runIfBusinessDay("09:51")

    @Scheduled(cron = "0 0 11 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1100() = runIfBusinessDay("11:00")

    @Scheduled(cron = "0 15 17 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1715() = runIfBusinessDay("17:15")

    @Scheduled(cron = "0 35 17 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1735() = runIfBusinessDay("17:35")

    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1800() = runIfBusinessDay("18:00")

    @Scheduled(cron = "0 15 18 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1815() = runIfBusinessDay("18:15")

    @Scheduled(cron = "0 35 18 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1835() = runIfBusinessDay("18:35")

    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Seoul")
    fun syncAt1900() = runIfBusinessDay("19:00")

    // ─── 영업일 게이트 ─────────────────────────────────────────────────

    /**
     * 영업일 확인 후 sync 실행 (ADR 0039 §2-2).
     * market_calendar row 우선, 미등록일은 BusinessDayCalendar fallback 적용.
     * cron 자체에 MON-FRI가 있으나, boot sync 등 임의 호출 시 중복 게이트 역할.
     */
    private fun runIfBusinessDay(cronTag: String) {
        val today = LocalDate.now(kst)
        if (!businessDayCalendar.isBusinessDay(today)) {
            log.info("[instrument-sync] cron={} skip — 휴장일 ({})", cronTag, today)
            return
        }
        try {
            val result = instrumentSyncJob.run(cronTag)
            log.info("[instrument-sync] $result")
        } catch (e: Exception) {
            log.error("[instrument-sync] cron={} 예외 발생 — err={}", cronTag, e.message)
        }
    }
}
