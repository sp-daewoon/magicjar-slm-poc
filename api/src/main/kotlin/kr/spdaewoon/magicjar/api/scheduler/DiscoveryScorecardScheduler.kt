package kr.spdaewoon.magicjar.api.scheduler

import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaRepository
import kr.spdaewoon.magicjar.api.service.screener.DiscoveryScorecardScoringService
import kr.spdaewoon.magicjar.api.service.screener.DiscoveryScorecardSnapshotService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 발굴 스코어카드 cron 트리거 (Flyway V77).
 *
 * 발굴 forward-test — 매일 장전 TOP N 기록 + 다음 거래일 실제 일봉으로 자동 채점.
 * 발굴 서비스([DiscoveryScorecardSnapshotService]·발굴 엔진)가 api 모듈에 있으므로 스케줄러도 api에 둔다
 * (consumer/batch에 없는 발굴 의존 그래프를 cross-module 주입 없이 그대로 재사용 — 모듈 경계 add-only).
 *
 * ## 2 cron (zone=Asia/Seoul · ENV 외부화 · 영업일 가드)
 *   - **snapshot (장전, default 08:30 KST)**: 활성 빌트인 DAYTRADE_* 프리셋 발굴 → TOP N INSERT(멱등).
 *     발굴 입력 = 전일 일봉이라 당일 일봉 없는 장전 시점이 정합. snapshotDate = 오늘(= 다음 거래일).
 *   - **scoring (야간, default 23:30 KST)**: R2 저녁 일봉 snapshot(20:05) 후 미채점 row를 당일 일봉으로 채점.
 *
 * ## 게이트 (3단)
 *   1. cron `zone=Asia/Seoul` — SpEL placeholder ENV 외부화.
 *   2. 영업일 가드 — market_calendar DB row + 주말 fallback (ConsumerBusinessDayCalendar 1:1 산식).
 *   3. ENV kill-switch — `magicjar.discovery-scorecard.enabled`(서비스 내부 DISABLED 즉시 종료).
 *
 * ## cron ENV 외부화 함정 (Spring @Scheduled 제약)
 *   @Scheduled(cron=...)는 컴파일 상수/SpEL placeholder만 받는다. cron 은
 *   `\${magicjar.discovery-scorecard.snapshot-cron:0 30 8 * * *}` 등 placeholder로 주입한다.
 *
 * 자본 영향 0 — 기록·관찰만 (주문/watchlist write 0). KIS 호출 0. AI 0.
 */
@Component
class DiscoveryScorecardScheduler(
    private val snapshotService: DiscoveryScorecardSnapshotService,
    private val scoringService: DiscoveryScorecardScoringService,
    private val marketCalendarJpaRepository: MarketCalendarJpaRepository,
) {
    private val log = LoggerFactory.getLogger(DiscoveryScorecardScheduler::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 장전 발굴 snapshot — default 08:30 KST 매 영업일.
     * ENV `MAGICJAR_DISCOVERY_SCORECARD_SNAPSHOT_CRON`(property `magicjar.discovery-scorecard.snapshot-cron`).
     */
    @Scheduled(cron = "\${magicjar.discovery-scorecard.snapshot-cron:0 30 8 * * *}", zone = "Asia/Seoul")
    fun runSnapshot() {
        val today = LocalDate.now(kst)
        if (!isBusinessDay(today)) {
            log.info("DiscoveryScorecardScheduler snapshot skip — KRX 비거래일 ({})", today)
            return
        }
        try {
            val result = snapshotService.runSnapshot(today)
            log.info(
                "DiscoveryScorecardScheduler snapshot 발사: status={} presets={} candidates={} inserted={}",
                result.status,
                result.presets,
                result.candidates,
                result.inserted,
            )
        } catch (e: Exception) {
            log.warn("DiscoveryScorecardScheduler snapshot 실패 (다음 영업일 재시도): err={}", e.message, e)
        }
    }

    /**
     * 야간 채점 — default 23:30 KST 매 영업일 (R2 저녁 일봉 snapshot 20:05 이후).
     * ENV `MAGICJAR_DISCOVERY_SCORECARD_SCORING_CRON`(property `magicjar.discovery-scorecard.scoring-cron`).
     */
    @Scheduled(cron = "\${magicjar.discovery-scorecard.scoring-cron:0 30 23 * * *}", zone = "Asia/Seoul")
    fun runScoring() {
        val today = LocalDate.now(kst)
        if (!isBusinessDay(today)) {
            log.info("DiscoveryScorecardScheduler scoring skip — KRX 비거래일 ({})", today)
            return
        }
        try {
            val result = scoringService.runScoring(today)
            log.info(
                "DiscoveryScorecardScheduler scoring 발사: status={} scanned={} scored={} skipped={}",
                result.status,
                result.scanned,
                result.scored,
                result.skipped,
            )
        } catch (e: Exception) {
            log.warn("DiscoveryScorecardScheduler scoring 실패 (다음 영업일 재시도): err={}", e.message, e)
        }
    }

    /**
     * 영업일 판정 — market_calendar DB row 우선, 미등록 시 주말 가드 fallback.
     * (ConsumerBusinessDayCalendar 산식 1:1 — api 모듈은 BusinessDayCalendar 빈이 없어 calendar repo로 직접 판정.)
     */
    private fun isBusinessDay(date: LocalDate): Boolean {
        val entry = marketCalendarJpaRepository.findById(date).orElse(null)
        if (entry != null) return entry.isBusinessDay
        val dow = date.dayOfWeek
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
    }
}
