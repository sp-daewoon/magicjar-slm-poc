package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.MarketDailySnapshotProperties
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketDailyNightlySnapshotScheduler — ADR 0118 R2 전종목 일봉 snapshot cron 트리거.
 *
 * - 매 영업일 **20:05 KST**(default · ENV `MAGICJAR_MARKET_DAILY_SNAPSHOT_CRON` 외부화): 당일 1d 발사.
 * - 매 영업일 **08:05 KST**(default · ENV `MAGICJAR_MARKET_DAILY_SNAPSHOT_PRE_OPEN_CRON` 외부화):
 *   직전 영업일 1d 장전 재확정. 20:05 실패·KIS 지연·야간 점검 공백이 사용자 상세 진입 가격에 남지 않게 한다.
 *
 * ## 게이트 (2단계)
 *   1. **cron `zone=Asia/Seoul`** — 발사 시각(SpEL `\${...:default}` 로 ENV 외부화).
 *   2. **영업일 가드** — [BusinessDayCalendar.isBusinessDay] (market_calendar DB → 공휴일·임시휴장 skip).
 *      InstrumentMasterSyncScheduler·MarketIndexScheduler 와 동일 KRX 영업일 판정 재사용(정합).
 *   3. **ENV kill-switch** — [MarketDailySnapshotProperties.enabled] (서비스 내부에서 DISABLED 즉시 종료).
 *
 * ## 분업 (★ ADR 0118 §2-C·§2-D LOCK — 본체는 [MarketDailyNightlySnapshotService])
 *   - R2(본 스케줄러, 20:05 당일 + 08:05 직전 영업일, 전종목): 대상일 1d **1봉만** 멀티시세 공식 종가로
 *     DO UPDATE(권위·후행 교정).
 *   - DailyCloseFinalizer(20:10, managed watchlist): Redis WS 집계 INSERT(DO NOTHING) — R2 성공값을 덮지 않고 실패 시 보조.
 *   - R-BF(MarketDailyBackfillCampaign, admin /run): 과거 10년 1d saveAll(DO NOTHING) — 과거봉 immutable.
 *   R2 는 today 1봉만 전진(과거 gap 채우기 아님). 시스템 며칠 down 시 누락 과거분은 R-BF 로 메운다.
 *
 * ## cron ENV 외부화 함정 (Spring @Scheduled 제약)
 *   @Scheduled(cron=...)는 컴파일 타임 상수 또는 SpEL property placeholder 만 받는다(메서드 호출 불가).
 *   따라서 cron 은 `\${magicjar.market-daily-snapshot.cron:0 5 20 * * *}` placeholder 로 주입한다
 *   ([MarketDailySnapshotProperties.cron] 과 동일 key·default — 정합). zone 은 상수 "Asia/Seoul" 고정.
 *
 * 자본 영향 0 — 데이터 적재만. LLM import 0 (룰 5).
 */
@Component
class MarketDailyNightlySnapshotScheduler(
    private val snapshotService: MarketDailyNightlySnapshotService,
    private val businessDayCalendar: BusinessDayCalendar,
) {
    private val log = LoggerFactory.getLogger(MarketDailyNightlySnapshotScheduler::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 저녁 snapshot cron — default 20:05 KST 매일 발사, 영업일만 실행.
     *
     * cron 은 ENV `MAGICJAR_MARKET_DAILY_SNAPSHOT_CRON`(property `magicjar.market-daily-snapshot.cron`)
     * 으로 외부화 — default `0 5 20 * * *`. 비거래일(주말·공휴일·임시휴장)은 영업일 가드로 skip.
     * ENV kill-switch(`...enabled`)는 서비스가 DISABLED 즉시 종료로 처리(콜 0).
     */
    @Scheduled(cron = "\${magicjar.market-daily-snapshot.cron:0 5 20 * * *}", zone = "Asia/Seoul")
    fun runNightlySnapshot() {
        val today = LocalDate.now(kst)
        if (!businessDayCalendar.isBusinessDay(today)) {
            log.info("MarketDailyNightlySnapshotScheduler skip — KRX 비거래일 ({})", today)
            return
        }
        try {
            val result = snapshotService.runSnapshot(today)
            log.info(
                "MarketDailyNightlySnapshotScheduler 발사 완료: status={} universe={} upserted={} durationMs={}",
                result.status,
                result.universeSize,
                result.upsertedRows,
                result.durationMs,
            )
        } catch (e: Exception) {
            // 야간 1회 best-effort — 실패해도 다음 영업일 재시도(누락 today 는 다음날 R-BF catch-up 가능).
            log.warn("MarketDailyNightlySnapshotScheduler 발사 실패 (다음 영업일 재시도): err={}", e.message, e)
        }
    }

    /**
     * 장전 snapshot cron — default 08:05 KST 매일 발사, 오늘이 영업일이면 직전 영업일 1d 를 재확정한다.
     *
     * 23시 job 을 단순히 08시로 옮기면 today row 에 전일/장전 값을 쓸 수 있으므로, 대상 날짜를 명시적으로
     * 직전 영업일로 계산한다.
     */
    @Scheduled(cron = "\${magicjar.market-daily-snapshot.pre-open-cron:0 5 8 * * *}", zone = "Asia/Seoul")
    fun runPreOpenSnapshot() {
        val today = LocalDate.now(kst)
        if (!businessDayCalendar.isBusinessDay(today)) {
            log.info("MarketDailyPreOpenSnapshotScheduler skip — KRX 비거래일 ({})", today)
            return
        }
        val targetDate = previousBusinessDay(today)
        try {
            val result = snapshotService.runSnapshot(targetDate)
            log.info(
                "MarketDailyPreOpenSnapshotScheduler 발사 완료: targetDate={} status={} universe={} upserted={} durationMs={}",
                targetDate,
                result.status,
                result.universeSize,
                result.upsertedRows,
                result.durationMs,
            )
        } catch (e: Exception) {
            log.warn(
                "MarketDailyPreOpenSnapshotScheduler 발사 실패 (다음 cron 재시도): targetDate={} err={}",
                targetDate,
                e.message,
                e,
            )
        }
    }

    private fun previousBusinessDay(from: LocalDate): LocalDate {
        var candidate = from.minusDays(1)
        repeat(14) {
            if (businessDayCalendar.isBusinessDay(candidate)) return candidate
            candidate = candidate.minusDays(1)
        }
        log.warn("MarketDailyPreOpenSnapshotScheduler previousBusinessDay fallback — 14일 내 영업일 미발견: from={}", from)
        return from.minusDays(1)
    }
}
