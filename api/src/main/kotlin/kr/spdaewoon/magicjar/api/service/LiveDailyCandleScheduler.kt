package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId

/**
 * 장중 일/주/월/년봉 자동 갱신 cron.
 *
 * 결함 배경 (2026-05-15 hotfix):
 *   - 분봉/시간봉(1m·3m·5m·15m·30m·90m·1h·4h·12h)은 KIS WS + BoundaryAggregator로 실시간 OK
 *   - 일/주/월/년봉은 전종목 일봉 universe(R-BF/R2 · ADR 0118) backfill 후 장중 polling 부재 → stale
 *     (구: ColdStartExecutor 1회 backfill — ADR 0119로 은퇴)
 *   - 사용자가 장중 SymbolDetail 진입 시 캔들이 며칠 전 데이터로 표시되는 결함
 *
 * 본 cron:
 *   - 5분 주기 (KIS Rate Limit ADR 0037 정합 + Watchlist 16종목 기준 0.8초 처리)
 *   - 장 시간 가드: 08:00~20:00 KST (NXT cover ADR 0063 정합)
 *   - 활성 watchlist 종목 distinct code → CandleRefreshService.refresh(code, ["1d"]) 호출
 *   - 일봉만 매 5분 갱신. 주/월/년봉은 별도 backlog 의제 (Phase 2)
 *
 * 환경 toggle: magicjar.live-candle.enabled (default true)
 */
@Component
class LiveDailyCandleScheduler(
    private val candleRefreshService: CandleRefreshService,
    private val watchlistJpaRepository: WatchlistJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    @Value("\${magicjar.live-candle.enabled:true}")
    private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(LiveDailyCandleScheduler::class.java)
    private val seoul = ZoneId.of("Asia/Seoul")
    private val marketOpen: LocalTime = LocalTime.of(8, 0)
    private val marketClose: LocalTime = LocalTime.of(20, 0)

    @Scheduled(fixedRate = 300_000L, initialDelay = 30_000L)
    fun tick() {
        if (!enabled) return

        val now = LocalTime.now(seoul)
        if (now.isBefore(marketOpen) || now.isAfter(marketClose)) {
            return
        }

        val userId = userAccountJpaRepository.findFirstUserIds(PageRequest.of(0, 1)).firstOrNull()
        if (userId == null) {
            log.debug("[LiveDailyCandle] user_account 부재 — skip")
            return
        }

        val watchlistRows = watchlistJpaRepository.findByUserIdOrderByPriorityAscCreatedAtDesc(userId)
        if (watchlistRows.isEmpty()) {
            log.debug("[LiveDailyCandle] watchlist 0건 — skip")
            return
        }

        val codes = watchlistRows.map { it.code }.distinct()
        val startMs = System.currentTimeMillis()
        var okCount = 0
        var failCount = 0

        // 종목 간 burst 완화 sleep 마진 (2026-05-22 hotfix — KIS_TPS_EXCEEDED LIVE root cause).
        //
        // 결함 배경 (`_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` §4-3):
        //   - 6 종목 × 1d × 평균 N 페이지 (최대 50)을 종목 간 sleep 0ms로 직렬 발사
        //     → Bucket4j(capacity=10, refillGreedy 10/s)는 통과 OK이나 KIS server-side가
        //       sliding window 또는 sub-second 분해 한도 적용 시 EGW00201 차단 발생.
        //   - 2시간 LIVE window evidence — count=109 누적 + ok=3 fail=3 (50% fail rate).
        //
        // 본 sleep 마진 (200ms):
        //   - Bucket4j 단일 bucket이 다른 사용자/scheduler caller에게도 token 회복 마진 제공
        //   - KIS sliding window 가설 (KIS 100ms당 1건 강제) 대비 안전 마진 확보
        //   - 6 종목 × 200ms = 1.2초 추가 elapsed (5분 cron 윈도 대비 0.4% — 영향 미미)
        //
        // 자본 영향: 0 (read-only 캔들 갱신 path). caller `LiveDailyCandleScheduler` 외부 사용 0.
        // Phase 3 후속: Circuit Breaker + 1초 backoff + 1회 재시도 (architect spec 권한 — backlog).
        val codeIntervalMs = 200L

        for ((index, code) in codes.withIndex()) {
            // 첫 종목은 sleep 마진 없이 즉시 진입 — 5분 cron 초기 burst가 가장 부담 적음
            if (index > 0) {
                try {
                    Thread.sleep(codeIntervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("[LiveDailyCandle] sleep interrupted — graceful stop")
                    break
                }
            }
            try {
                // priority=BACKFILL 전달 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §C).
                //
                // 결함 evidence (`_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` §8):
                //   - PR #658 (종목 간 200ms sleep) 7 cycle ok=0 fail=6 → H1·H4 root cause 확정
                //   - 본 scheduler는 사용자 path 와 동일 USER_PATH로 acquire → bucket 경쟁 → USER_PATH 사용자가 cron에 막힘
                //
                // 본 fix: priority=BACKFILL 전달 → CandleRefreshService 내부 acquire가 BACKFILL 라벨로 호출
                //   - Phase 1 단계는 단일 bucket이므로 효과는 정보 수집만 (Phase 2 priority queue 활성화 시 사용자 path 우선)
                //   - 그럼에도 본 PR에서 priority 분리는 architect spec 권한 — Phase 2 활성화 전 사전 합류 의무
                //
                // incremental=true (2026-06-04 KIS_TPS_EXCEEDED 점검 #2 root fix):
                //   - 기존: 매 5분 cycle 종목마다 cursor=today에서 30년 전까지 maxPages(1d=50) 전수 재fetch
                //     → 6 종목 × 50 페이지 = cycle당 최대 300 KIS 호출 burst → EGW00201 단조 증가(57→126/일)
                //   - 신규: DB 최신 봉까지만 fetch(delta-only) → 종목당 ~1 페이지 → cron burst 제거
                //   - 과거 일봉은 immutable + upsert ON CONFLICT DO NOTHING → 재fetch는 순수 낭비였음
                //   - 사용자 트리거(SymbolController.refresh)는 incremental=false default → 전수 backfill 보존
                val response = candleRefreshService.refresh(code, listOf("1d"), KisCallPriority.BACKFILL, incremental = true)
                val ok = response.refreshedIntervals.all { it.status == "OK" }
                if (ok) okCount++ else failCount++
            } catch (e: Exception) {
                failCount++
                log.warn("[LiveDailyCandle] code={} 갱신 실패: {}", code, e.message)
            }
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        log.info(
            "[LiveDailyCandle] {} 종목 갱신 완료 — ok={} fail={} elapsedMs={}",
            codes.size,
            okCount,
            failCount,
            elapsedMs,
        )
    }
}
