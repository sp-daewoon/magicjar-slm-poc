package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * MARKET_DAILY 10년 일봉 backfill 캠페인 설정 (ADR 0118 R-BF).
 *
 * 멱등 캠페인: 한 실행에 [nightlyCallCap] 콜만 소비하고, 미완료 종목은 다음 실행으로 이연한다.
 * progress 는 candle row 존재 기반 산출(별도 진행 테이블 0 — Flyway 0). [enabled] off 시 즉시 중단(적재분 보존).
 *
 * ENV 매핑 (`magicjar.market-daily-backfill.*` — application.yml + 환경변수 override):
 *
 * | key | ENV | default | 의미 |
 * |-----|-----|---------|------|
 * | enabled | MAGICJAR_MARKET_DAILY_BACKFILL_ENABLED | false | 캠페인 시동/중단 게이트. off=가동 0(적재분 보존) |
 * | history-years | MAGICJAR_MARKET_DAILY_BACKFILL_HISTORY_YEARS | 10 | 이력 깊이(결재 ② LOCK 10년) |
 * | nightly-call-cap | MAGICJAR_MARKET_DAILY_BACKFILL_NIGHTLY_CALL_CAP | 6000 | 한 실행당 KIS 콜 상한. 초과분 다음 실행 이연 |
 * | completion-min-rows | MAGICJAR_MARKET_DAILY_BACKFILL_COMPLETION_MIN_ROWS | 200 | 종목 완료 판정 최소 row 수(candle-row 기반 progress) |
 * | recency-grace-days | MAGICJAR_MARKET_DAILY_BACKFILL_RECENCY_GRACE_DAYS | 7 | 최신 봉이 today 로부터 이 일수 이내면 "최근까지 적재됨"으로 본다 |
 * | no-advance-cooldown-days | MAGICJAR_MARKET_DAILY_BACKFILL_NO_ADVANCE_COOLDOWN_DAYS | 7 | fetch 했으나 max_ts 전진 0(KIS-unavailable) 종목 재시도 cooldown 일수(over-fetch 가드) |
 * | concurrency | MAGICJAR_MARKET_DAILY_BACKFILL_CONCURRENCY | 10 | 동시 처리 종목 수(coroutine Semaphore permits). throughput shaping — KIS hard ceiling 은 별도 |
 * | launch-delay-ms | MAGICJAR_MARKET_DAILY_BACKFILL_LAUNCH_DELAY_MS | 100 | 종목 launch 간 지연(ms). 동시 burst 분산 — rate-limiter 압박 완화 |
 *
 * ## 동시성 보강 (ADR 0118 amend) — Semaphore vs rate-limiter 책임 분리
 *   기존 종목별 동기 순차 루프(1.5~2h/run)가 단일호출 지연으로 느렸다. coroutine Semaphore([concurrency]
 *   permits) + 종목 launch 당 [launchDelayMs] 지연으로 비동기 전환하여 throughput 을 높인다.
 *   **단 KIS hard ceiling 은 `ConsumerRedisKisRateLimiterAdapter`(Bucket4j Redis bucket)가 그대로 유효** —
 *   세마포어는 동시 종목 수만 제한(throughput shaping)하며 rate limiter 를 우회·중복하지 않는다.
 *   call-cap·audit·progress·no-advance 회계는 동시성에서 원자적 카운터(AtomicInteger/Long)로 보호.
 *
 * ## nightly-call-cap 산정 근거 (ADR 0118 §2-B·§5-1)
 *   전종목 10년 ≈ 75,000콜. 야간 윈도(저녁 snapshot 약 100콜 선소비 후 잔여)에서 밤당 일정 예산만 소비.
 *   default 6,000 = BG 버킷 3/s 기준 ~33분(순수 호출). jitter·재시도 포함 ~1~2시간/밤 → 며칠~한 주 분산 완주.
 *   사용자 환경에서 ENV 로 야간 윈도 길이에 맞춰 조정.
 *
 * ## progress = candle row 기반 산출 (진행 테이블 0)
 *   ADR §2-B 가 "진행 테이블 vs candle row 존재 기반 산출" 중 구현 단계 택1 위임.
 *   **candle row 기반** 선택 — 사유: (a) Flyway 신규 0(api 모듈 V76 cross-boundary 회피),
 *   (b) saveAll 이 ON CONFLICT DO NOTHING 이라 candle row 자체가 진실의 단일 출처,
 *   (c) 별도 progress 테이블은 candle 과 drift risk(이중 기록). 완료 판정 = row 수 ≥ [completionMinRows]
 *   AND 최신 봉이 [recencyGraceDays] 이내(또는 상장 후 거래일 부족분 보정 — clamp 와 합산 판단).
 */
@ConfigurationProperties(prefix = "magicjar.market-daily-backfill")
data class MarketDailyBackfillProperties(
    val enabled: Boolean = false,
    val historyYears: Int = 10,
    val nightlyCallCap: Int = 6_000,
    val completionMinRows: Int = 200,
    val recencyGraceDays: Int = 7,
    val noAdvanceCooldownDays: Int = 7,
    val concurrency: Int = 10,
    val launchDelayMs: Long = 100L,
) {
    fun normalizedHistoryYears(): Int = historyYears.coerceIn(1, 30)

    fun normalizedNightlyCallCap(): Int = nightlyCallCap.coerceAtLeast(1)

    fun normalizedCompletionMinRows(): Int = completionMinRows.coerceAtLeast(1)

    fun normalizedRecencyGraceDays(): Long = recencyGraceDays.coerceAtLeast(0).toLong()

    /** no-advance cooldown 일수 — 0 이면 cooldown 비활성(매 run 재시도). */
    fun normalizedNoAdvanceCooldownDays(): Long = noAdvanceCooldownDays.coerceAtLeast(0).toLong()

    /** 동시 처리 종목 수(coroutine Semaphore permits). 1=완전 직렬. 상한 32(KIS 압박·DB 풀 보호). */
    fun normalizedConcurrency(): Int = concurrency.coerceIn(1, 32)

    /** 종목 launch 간 지연(ms). 0=지연 없음. 상한 10초(과도한 지연 방어). */
    fun normalizedLaunchDelayMs(): Long = launchDelayMs.coerceIn(0L, 10_000L)
}
