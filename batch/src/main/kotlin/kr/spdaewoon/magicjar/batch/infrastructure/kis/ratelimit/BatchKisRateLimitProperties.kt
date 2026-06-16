package kr.spdaewoon.magicjar.batch.infrastructure.kis.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * batch 모듈 KIS Rate Limit 설정 (ADR 0116 Phase 2 3-tier — api·consumer Properties 1:1).
 *
 * 기본값 LOCK (ADR 0037 §1 KIS 공식 한도 — 초당 10건 · ADR 0116 단일 bucket supersede):
 *   - bucketCapacity=10
 *   - bucketRefillTokens=10
 *   - bucketRefillPeriodSeconds=1
 *   - bucketKey="kis-rate-limit:global" (★ api·consumer Properties와 동일 LOCK — Redis-level share)
 *
 * Phase 2 priority별 별도 버킷 (ADR 0116 §2-B):
 *   - api·consumer + 본 batch Properties 동일 prefix·동일 bucketKey·동일 capacity LOCK
 *   - multi-JVM 버킷 share = Bucket4j Lettuce backend Redis ZSET 키(:user·:shared·:bg) share
 *   - batch는 BACKFILL·INGESTION 호출만 발행 (USER_PATH 0) → BG·SHARED 버킷만 소비, USER 버킷 미침범
 *   - 단일 source-of-truth = `magicjar.kis.rate-limiter.*` env keys
 *
 * 합류 대상 어댑터 (T-D-2 D-2 — 1일 1회 cron이라 risk 낮지만 일괄 backfill 시 burst 가능):
 *   - KisChkHolidayAdapter (CTCA0903R, 영업일 정보)
 *   - KisInstrumentMasterAdapter (KOSPI/KOSDAQ 종목마스터 zip 다운로드)
 *   - KisKsdInfoAdapter (6 ksdinfo endpoint — 배당/분할/합병/증자/감자)
 */
@ConfigurationProperties(prefix = "magicjar.kis.rate-limiter")
data class BatchKisRateLimitProperties(
    /** 어댑터 활성화 스위치 (default true — 활성). false 시 NoOp 패턴 fallback. */
    val enabled: Boolean = true,
    /** 전체 bucket 토큰 capacity (KIS 공식 한도 = 10). priorityBucketsEnabled=false 시 단일 :global 버킷 회귀. */
    val bucketCapacity: Long = 10L,
    /** refill 1회 토큰 수 (= capacity → 매 refill 윈도 100% 회복). */
    val bucketRefillTokens: Long = 10L,
    /**
     * priority별 별도 버킷(3-tier) 활성 스위치 (ADR 0116 §2-B · default true).
     * ★ api·consumer Properties와 동일 default LOCK (multi-JVM 동일 Redis 버킷 정합).
     */
    val priorityBucketsEnabled: Boolean = true,
    /** USER 버킷 capacity — batch는 USER_PATH 0이라 미접근이나 합 검증·정합 위해 동일 보유. */
    val bucketUser: Long = 4L,
    /** REALTIME_MARKET 버킷 capacity — ACTIVE 전략 degraded 시세 보강. 0이면 legacy 3-bucket 라우팅. */
    val bucketRealtimeMarket: Long = 0L,
    /** SHARED 버킷 capacity — INGESTION 1차 (batch INGESTION 호출). */
    val bucketShared: Long = 3L,
    /** BG 버킷 capacity — BACKFILL·INGESTION_LOW 1차 (batch 캔들 backfill·corp action). */
    val bucketBg: Long = 3L,
    /**
     * USER_PATH 전용 예약 토큰 수 — **deprecated (ADR 0116 §4 — priorityBucketsEnabled로 일반화)**.
     * Phase 1.5 2-bucket reserve 잔재. 하위호환 바인딩 보존.
     */
    @Deprecated("ADR 0116 — priorityBucketsEnabled로 일반화. 미사용(바인딩 호환 보존)")
    val userPathReserveTokens: Long = 3L,
    /** refill 윈도 초 (KIS 한도 = 1초). */
    val bucketRefillPeriodSeconds: Long = 1L,
    /** Redis ZSET 키 (★ api·consumer Properties와 동일 LOCK — multi-JVM 단일 bucket share). */
    val bucketKey: String = "kis-rate-limit:global",
    /** Bucket4j Redis backend TTL (refill 윈도의 10배 — 안전 마진). */
    val bucketTtlSeconds: Long = 10L,
    /** acquire() polling 간격 (token 부족 시 다음 interval refill 윈도우를 확인하는 sleep). */
    val acquirePollIntervalMillis: Long = 50L,
    /**
     * 알람 차등화 윈도 임계 (2026-06-10 노이즈 진단 hotfix · api·consumer Properties 동일 LOCK).
     *
     * 5분 aggregator 윈도 내 EGW00201/429 발생 건수가 threshold 미만 → INFO(알림센터 기록만),
     * 이상 → WARN(현행 알람). env override: MAGICJAR_KIS_RATE_LIMITER_TPS_EXCEEDED_WARN_THRESHOLD.
     */
    val tpsExceededWarnThreshold: Int = 5,
)
