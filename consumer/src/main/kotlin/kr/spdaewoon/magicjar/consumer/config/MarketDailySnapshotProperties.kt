package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * MARKET_DAILY 저녁/장전 전종목 일봉 snapshot 스케줄러 설정 (ADR 0118 R2).
 *
 * 매 영업일 저녁(default 20:05 KST) + 다음 영업일 장전(default 08:05 KST) 멀티시세(FHKST11300006)로
 * 전종목 보통주(~2,517)의 대상 영업일 일봉 1개를 KIS 공식 마감값으로 upsert(ON CONFLICT DO UPDATE) 한다.
 * R-BF(과거 10년 backfill)·managed(DailyCloseFinalizer 20:10·LastCandleAtMonitor 18:00)와 **분업**:
 *
 * | 잡 | 시각 | universe | interval | 경로 | 권위 |
 * |----|------|----------|----------|------|------|
 * | **R2 snapshot(본 설정)** | **20:05 + 08:05** | **전종목 ~2,517** | **대상일 1d** | **멀티시세 upsert(DO UPDATE)** | **KIS 공식 종가(권위·후행 교정)** |
 * | DailyCloseFinalizer | 20:10 | managed(watchlist) | 1d | Redis WS 집계 INSERT(DO NOTHING) | R2 실패 시 managed fallback |
 * | R-BF backfill | admin /run·야간 | 전종목 | 과거 1d(10년) | range fetch saveAll(DO NOTHING) | 과거봉 immutable |
 *
 * R2 는 **today 1봉만** 전진시킨다(과거 gap 채우기 아님 — 그건 R-BF 소관). 시스템 며칠 down 시 누락
 * 과거분은 R-BF(`POST /admin/market-daily-backfill/run`)로 메운다.
 *
 * ENV 매핑 (`magicjar.market-daily-snapshot.*` — application.yml + 환경변수 override):
 *
 * | key | ENV | default | 의미 |
 * |-----|-----|---------|------|
 * | enabled | MAGICJAR_MARKET_DAILY_SNAPSHOT_ENABLED | true | snapshot 시동/중단 kill-switch. off=가동 0(운영 점검 시) |
 * | cron | MAGICJAR_MARKET_DAILY_SNAPSHOT_CRON | 0 5 20 * * * | 저녁 발사 cron (zone=Asia/Seoul 고정). NXT 20:00 마감 후 |
 * | pre-open-cron | MAGICJAR_MARKET_DAILY_SNAPSHOT_PRE_OPEN_CRON | 0 5 8 * * * | 장전 보정 cron. 직전 영업일 1d 를 재확정 |
 * | chunk-spacing-ms | MAGICJAR_MARKET_DAILY_SNAPSHOT_CHUNK_SPACING_MS | 120 | chunk(30종목/콜) 간 가벼운 지연(ms) — burst 분산. rate-limiter hard ceiling 은 별도 |
 *
 * ## rate-limit (ADR 0116 · 0118 §2-C·§5-1 LOCK)
 *   멀티시세는 가벼우나(~84콜) 모든 KIS 콜은 [kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisMultiPriceAdapter]
 *   내부에서 BACKFILL(BG) 버킷 경유. USER_PATH·INGESTION 절대 침범 0 — 사용자·자본 path 영향 0.
 *   chunk 간 [chunkSpacingMs] spacing 으로 단일 대량 동시호출 회피(2026-06-09 wipe→재적재 교훈).
 *
 * ## cron=20:05 근거 (ADR 0118 §2-C amend)
 *   NXT after-hour 마감 20:00 KST 직후 공식값을 빠르게 교정한다.
 *   20:10 DailyCloseFinalizer 는 INSERT DO NOTHING 이라 R2 성공값을 덮지 않고, 08:05 장전 job 이 한 번 더 재확정한다.
 */
@ConfigurationProperties(prefix = "magicjar.market-daily-snapshot")
data class MarketDailySnapshotProperties(
    val enabled: Boolean = true,
    val cron: String = DEFAULT_CRON,
    val preOpenCron: String = DEFAULT_PRE_OPEN_CRON,
    val chunkSpacingMs: Long = 120L,
) {
    companion object {
        /**
         * default cron — 매일 20:05:00 KST 1회. zone 은 @Scheduled(zone="Asia/Seoul") 에서 고정.
         * 영업일 가드는 스케줄러가 BusinessDayCalendar 로 2차 필터(공휴일·임시휴장 skip).
         */
        const val DEFAULT_CRON = "0 5 20 * * *"
        const val DEFAULT_PRE_OPEN_CRON = "0 5 8 * * *"
    }

    /** chunk 간 지연(ms). 0=지연 없음. 상한 5초(과도한 지연 방어). */
    fun normalizedChunkSpacingMs(): Long = chunkSpacingMs.coerceIn(0L, 5_000L)
}
