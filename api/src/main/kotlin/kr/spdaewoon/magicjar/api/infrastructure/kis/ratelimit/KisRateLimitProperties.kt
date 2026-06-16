package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS Rate Limit 설정 (ADR 0116 Phase 2 — priority별 별도 버킷 3-tier).
 *
 * 기본값 LOCK (ADR 0037 §1 KIS 공식 한도 — 초당 10건 · ADR 0116이 단일 bucket supersede):
 *   - bucketCapacity=10
 *   - bucketRefillTokens=10
 *   - bucketRefillPeriodSeconds=1
 *
 * Phase 2 priority별 별도 버킷 (ADR 0116 §2-B):
 *   - USER 버킷(capacity=bucketUser=4): USER_PATH 1차 — 주문·현재가·차트·잔고
 *   - SHARED 버킷(capacity=bucketShared=3): INGESTION 1차 — watchlist·approval
 *   - BG 버킷(capacity=bucketBg=3): BACKFILL·INGESTION_LOW 1차 — 캔들 backfill·지수/섹터 cron
 *   - 합 = 10 (KIS 초당 한도 정확 — 미초과·증설 0)
 *   - 역방향 fallback: USER_PATH→[user,shared,bg]·INGESTION→[shared,bg]·BG→[bg]
 *     (상위 priority가 더 많은 버킷 접근 → 하위 burst가 상위를 굶기지 못함)
 *   - priorityBucketsEnabled=false → 단일 :global 버킷(capacity 10) 회귀 (Phase 1 안전 최소 — rollback)
 *
 * Phase 1: Circuit Breaker 통계 윈도/임계는 정의만 (실 구현 Phase 3 후속).
 *
 * env override (rollback path — ADR 0116 §5-2 정합):
 *   - MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED=false → 단일 bucket(capacity 10) 회귀
 *   - MAGICJAR_KIS_RATE_LIMIT_BURST=15 → bucketCapacity=15 (KIS 차단 risk 동반 — 사용자 알림 의무)
 */
@ConfigurationProperties(prefix = "magicjar.kis.rate-limiter")
data class KisRateLimitProperties(
    /**
     * 어댑터 활성화 스위치 (default true — 활성).
     * false 시 NoOpKisRateLimiterAdapter fallback (테스트·단일 JVM 부팅 path).
     */
    val enabled: Boolean = true,
    /**
     * 전체 bucket 토큰 capacity (KIS 공식 한도 = 10).
     * priorityBucketsEnabled=false 시 이 capacity의 단일 :global 버킷으로 회귀.
     */
    val bucketCapacity: Long = 10L,
    /** refill 1회 토큰 수 (= capacity → 매 refill 윈도 100% 회복). */
    val bucketRefillTokens: Long = 10L,
    /**
     * priority별 별도 버킷(3-tier) 활성 스위치 (ADR 0116 §2-B · default true).
     *
     * - true: USER/SHARED/BG 3 버킷 + 역방향 fallback 라우팅.
     * - false: 단일 :global 버킷(capacity=bucketCapacity) 회귀 — Phase 1 안전 최소 (rollback path).
     *
     * 합(bucketUser+bucketShared+bucketBg) ≠ bucketCapacity 면 부팅 시 WARN + 단일 bucket fallback (fail-safe).
     */
    val priorityBucketsEnabled: Boolean = true,
    /** USER 버킷 capacity — USER_PATH 1차 (주문·현재가·차트·잔고). 합 검증: user+shared+bg=bucketCapacity. */
    val bucketUser: Long = 4L,
    /** REALTIME_MARKET 버킷 capacity — ACTIVE 전략 degraded 시세 보강. 0이면 legacy 3-bucket 라우팅. */
    val bucketRealtimeMarket: Long = 0L,
    /** SHARED 버킷 capacity — INGESTION 1차 (watchlist·approval). USER_PATH fallback 2단. */
    val bucketShared: Long = 3L,
    /** BG 버킷 capacity — BACKFILL·INGESTION_LOW 1차 (캔들 backfill·지수/섹터 cron). USER_PATH·INGESTION fallback 말단. */
    val bucketBg: Long = 3L,
    /**
     * USER_PATH 전용 예약 토큰 수 — **deprecated (ADR 0116 §4 — priorityBucketsEnabled로 일반화)**.
     *
     * Phase 1.5 2-bucket reserve 모델의 잔재. priorityBucketsEnabled=true(default)면 미사용.
     * priorityBucketsEnabled=false(rollback) 경로는 단일 bucket(capacity)으로 회귀하므로 이 값도 미사용.
     * 하위호환 ConfigurationProperties 바인딩 유지를 위해 보존 (제거 시 기존 ENV override 깨짐).
     */
    @Deprecated("ADR 0116 — priorityBucketsEnabled로 일반화. 미사용(바인딩 호환 보존)")
    val userPathReserveTokens: Long = 3L,
    /**
     * refill 윈도 초 (KIS 한도 = 1초).
     */
    val bucketRefillPeriodSeconds: Long = 1L,
    /**
     * Redis ZSET 키 (multi-JVM share — 단일 글로벌 bucket).
     */
    val bucketKey: String = "kis-rate-limit:global",
    /**
     * Bucket4j Redis backend TTL (refill 윈도의 10배 — 안전 마진).
     */
    val bucketTtlSeconds: Long = 10L,
    /**
     * acquire() polling 간격 (token 부족 시 재시도 sleep).
     *
     * Phase 1 단순화: BucketProxy는 `tryConsume` non-blocking 위주 → timeout 내 short sleep polling.
     * interval refill 전환 후에는 다음 1초 윈도우 진입을 짧게 확인한다.
     */
    val acquirePollIntervalMillis: Long = 50L,
    /**
     * 알람 차등화 윈도 임계 (2026-06-10 노이즈 진단 hotfix · ADR 0095 S-4 임계 정렬).
     *
     * 5분 aggregator 윈도 내 EGW00201/429 발생 건수가:
     *   - 1 ~ (threshold-1) → SystemEventSeverity.INFO (알림센터 기록만 — 사용자 toast/Slack 미발송)
     *   - >= threshold      → SystemEventSeverity.WARN (현행 알람 경로)
     *
     * default 5 — 산발 EGW00201(자동 backoff 회복, 운영 무해)은 INFO로 격하, burst만 WARN으로 사용자 도달.
     * env override: MAGICJAR_KIS_RATE_LIMITER_TPS_EXCEEDED_WARN_THRESHOLD.
     */
    val tpsExceededWarnThreshold: Int = 5,
    /**
     * Circuit Breaker — fail rate 임계 (default 0.5 = 50%). Phase 3 합류.
     */
    val circuitBreakerFailureRate: Double = 0.5,
    /**
     * Circuit Breaker — 윈도 초 (default 300 = 5분). Phase 3 합류.
     */
    val circuitBreakerWindowSeconds: Long = 300L,
    /**
     * Circuit Breaker — OPEN 유지 초 (default 300 = 5분). Phase 3 합류.
     */
    val circuitBreakerOpenDurationSeconds: Long = 300L,
)
