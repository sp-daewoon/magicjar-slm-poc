package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS Rate Limit Bucket4j-Redis 3-tier priority 버킷 adapter (ADR 0116 §2-B Phase 2).
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 (ADR 0116이 단일 bucket → 3-tier supersede)
 *   - ADR 0038 — Redis 인프라 재사용 (multi-JVM 버킷 share)
 *   - ADR 0116 — priority별 별도 버킷(3-tier) + 역방향 fallback + KIS 한도 정합
 *
 * 책임:
 *   - Bucket4j 8.10.1 + Lettuce Redis backend로 priority별 버킷 발행
 *   - `acquire(priority, timeoutMillis)` blocking polling 패턴 (tryConsume non-blocking + short sleep retry)
 *   - EGW00201/HTTP 429 시그널 누적 (Phase 1 log 카운터만 — Phase 3 Circuit Breaker 합류 후속)
 *
 * priority별 별도 버킷 (3-tier · ADR 0116 §2-B):
 *   - USER 버킷(capacity=bucketUser=4): USER_PATH 1차 — 주문·현재가·차트·잔고
 *   - SHARED 버킷(capacity=bucketShared=3): INGESTION 1차 — watchlist·approval
 *   - BG 버킷(capacity=bucketBg=3): BACKFILL·INGESTION_LOW 1차 — 캔들 backfill·지수/섹터 cron
 *   - 합 = bucketCapacity=10 (KIS 초당 한도 정확)
 *
 * 역방향 fallback 라우팅 (상위 priority일수록 더 많은 버킷 접근 — 하위 burst가 상위 굶기지 못함):
 *   - USER_PATH  → [USER, SHARED, BG]  (주문은 절대 안 막힘이 최우선 — 3 버킷 모두 소비 가능)
 *   - INGESTION  → [SHARED, BG]        (BG burst로부터 보호 + USER 버킷은 보존)
 *   - BACKFILL / INGESTION_LOW → [BG]  (자기 버킷 소진 시 polling 대기 → timeout, 상위 미침범)
 *
 * 단일 bucket 회귀 (priorityBucketsEnabled=false — ADR 0116 §5-2 rollback):
 *   - 모든 priority가 단일 :global 버킷(capacity=bucketCapacity) 1개만 소비 (Phase 1 안전 최소)
 *
 * Circuit Breaker LOCK:
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회)
 *   - 역방향 fallback으로 비주문 burst가 주문(USER_PATH)을 굶기는 EGW00201 직격 risk 차단 (14:59 evidence)
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 `tryConsume(1)` non-blocking + polling 패턴 — BlockingBucket 미지원 distributed proxy 회피
 *   - 🔄 Bucket4j 8.x ProxyManager Lettuce backend 채택 (Redisson 의존 0 — bundle bloat 회피)
 *   - ✨ 통계 카운터는 AtomicLong (Phase 3 Resilience4j Circuit Breaker 합류 시 위임)
 *   - ✨ 버킷 리스트 순회로 일반화 — `when` exhaustive priority→버킷 매핑 + 2단 reserve를 N단 fallback으로 확장
 */
class RedisKisRateLimiterAdapter(
    private val proxyManager: ProxyManager<String>,
    private val props: KisRateLimitProperties,
    /**
     * SystemEvent emit port (진단 PR #639 P0 hotfix — KIS_RATELIMIT P3 catalog → P1 활성).
     *
     * nullable LOCK 사유 — Bean wiring 단계 분리 + persistence-shared 의존 없는 단위 테스트 허용.
     * production 환경에서는 [KisRateLimiterConfiguration]가 Bean 주입 의무.
     */
    private val systemEventLogger: SystemEventLogger? = null,
    /**
     * Micrometer 관측 registry (ADR 0116 §2-E · R2).
     *
     * nullable LOCK 사유 — 기존 단위 테스트(MeterRegistry 미주입) 무수정 보존 + Actuator 미존재 fixture 안전.
     * production 에서는 [KisRateLimiterConfiguration]가 @Autowired(required=false)로 주입.
     *
     * 노출 metric (Prometheus `/actuator/prometheus`):
     *   - `kis_ratelimit_bucket_consumed_total{bucket=user|shared|bg|global}` — 버킷별 토큰 소진(사용률)
     *   - `kis_ratelimit_acquire_timeout_total{priority}` — 어느 priority 가 굶었는지
     *   - `kis_ratelimit_tps_exceeded_total{last_bucket}` — EGW00201 직전 마지막 소진 버킷
     */
    private val meterRegistry: MeterRegistry? = null,
) : KisRateLimiterPort {
    private val log = LoggerFactory.getLogger(RedisKisRateLimiterAdapter::class.java)

    /** Phase 1 통계 카운터 (Phase 3 Circuit Breaker에서 권위 위임) — 부팅 후 누적치(리셋 없음). */
    private val tpsExceededCount = AtomicLong(0L)
    private val successCount = AtomicLong(0L)

    /**
     * 알람 차등화 윈도 카운터 (2026-06-10 노이즈 진단 hotfix).
     *
     * 진보 페르소나 (ADR 0087) 결정:
     *   - 💡 **AtomicLong getAndSet(0)** — emit 시점에 "이번 윈도 발생 건수" 스냅샷 + lock-free 리셋.
     *   - 🔄 누적 [tpsExceededCount]는 detailJson이 "최근 5분 건수"로 오인되는 원인 → windowCount로 1차 노출 분리.
     *   - ✨ 윈도 건수 < [KisRateLimitProperties.tpsExceededWarnThreshold] → INFO(알림센터 기록만),
     *     ≥ 임계 → WARN(현행 알람). 산발 EGW00201(자동 backoff 회복) 노이즈를 INFO로 격하 — burst만 WARN.
     */
    private val windowTpsCount = AtomicLong(0L)

    /**
     * 최근 SystemEvent emit 시각 — spam 회피 aggregator (5분당 1회 emit LOCK).
     *
     * 진보 페르소나 (ADR 0087) 결정:
     *   - 💡 **AtomicReference<Instant>** — lock-free CAS 갱신 (multi-thread acquire 호출 안전)
     *   - ✨ aggregator pattern — 사용자 알림 spam 차단 + DB 부하 회피. EGW00201 burst 시 5분당 1건만 emit
     *   - 🔄 매 호출 emit 옵션은 P3 plan ratelimit-event spec 후속 검토 (현 hotfix scope P0 안정 LOCK)
     */
    private val lastEmittedAt = AtomicReference<Instant>(Instant.EPOCH)

    /**
     * 최근 소진 성공한 버킷 라벨 — EGW00201 발생 시 어느 priority 버킷이 직전 소진됐는지 추적 (ADR 0116 §2-E).
     *
     * `tryConsumeRouted` 성공 시 CAS-free set, `onTpsExceeded` 시 read하여 metric `last_bucket` 라벨 첨부.
     * 진보 페르소나 (ADR 0087): ✨ lock-free AtomicReference — multi-thread acquire 경합 안전.
     */
    private val lastConsumedBucket = AtomicReference(BUCKET_NONE)

    private companion object {
        /** SystemEvent emit aggregator 임계 — 5분당 최대 1회 emit (사용자 알림 spam 회피) */
        val EMIT_AGGREGATOR_WINDOW: Duration = Duration.ofMinutes(5)

        const val ACTOR = "RedisKisRateLimiterAdapter"
        const val ACTION_TPS_EXCEEDED = "KIS_TPS_EXCEEDED"

        /** priority별 버킷 key suffix (base bucketKey 와 동일 prefix + suffix · ADR 0116 §2-B Redis 3 key). */
        const val USER_KEY_SUFFIX = ":user"
        const val REALTIME_KEY_SUFFIX = ":realtime"
        const val SHARED_KEY_SUFFIX = ":shared"
        const val BG_KEY_SUFFIX = ":bg"

        /** Micrometer metric 이름 (ADR 0116 §2-E · §5-3 Prometheus query 정합). */
        const val METRIC_BUCKET_CONSUMED = "kis.ratelimit.bucket.consumed"
        const val METRIC_ACQUIRE_TIMEOUT = "kis.ratelimit.acquire.timeout"
        const val METRIC_TPS_EXCEEDED = "kis.ratelimit.tps.exceeded"

        /**
         * tr_id별 KIS 호출량 counter (ADR 0116 §2-E 연장 — 어느 KIS API가 TPS를 먹는지).
         * `kis_call_total{tr_id, priority, result}` — Prometheus 노출 시 `_total` suffix.
         */
        const val METRIC_KIS_CALL = "kis.call"

        /** tr_id null 시 라벨 (cardinality: tr_id는 유한 상수 집합 — unknown 1 series 추가 안전). */
        const val TR_ID_UNKNOWN = "unknown"

        /** acquire 결과 라벨 (`result` tag) — counter 분모/분자 분리. */
        const val RESULT_OK = "ok"
        const val RESULT_TIMEOUT = "timeout"

        /** 버킷 라벨 (metric tag `bucket` / `last_bucket`). */
        const val BUCKET_USER = "user"
        const val BUCKET_REALTIME = "realtime"
        const val BUCKET_SHARED = "shared"
        const val BUCKET_BG = "bg"
        const val BUCKET_GLOBAL = "global"
        const val BUCKET_NONE = "none"
    }

    /** 4-tier 합 = bucketCapacity 인지. */
    private val realtimeBucketSumValid: Boolean =
        (props.bucketUser + props.bucketRealtimeMarket + props.bucketShared + props.bucketBg) == props.bucketCapacity &&
            props.bucketRealtimeMarket > 0L

    /** 기존 3-tier 합 = bucketCapacity 인지. 구버전 ENV 호환용. */
    private val legacyBucketSumValid: Boolean =
        (props.bucketUser + props.bucketShared + props.bucketBg) == props.bucketCapacity

    private val bucketSumValid: Boolean = realtimeBucketSumValid || legacyBucketSumValid

    private val realtimeBucketActive: Boolean = realtimeBucketSumValid

    /**
     * 3-tier priority 버킷 활성 여부.
     *   - priorityBucketsEnabled=true + 합 검증 통과 + 각 버킷 > 0 → 3-tier 라우팅
     *   - 그 외 → 단일 :global 버킷(capacity=bucketCapacity) 회귀 (Phase 1 안전 최소 — rollback)
     */
    private val priorityBucketsActive: Boolean =
        props.priorityBucketsEnabled &&
            bucketSumValid &&
            props.bucketUser > 0L &&
            props.bucketShared > 0L &&
            props.bucketBg > 0L

    init {
        if (props.priorityBucketsEnabled && !bucketSumValid) {
            log.warn(
                "[KisRateLimit] priority 버킷 합 불일치 — 단일 bucket fallback (fail-safe): user={} realtime={} shared={} bg={} sum={} != capacity={}",
                props.bucketUser,
                props.bucketRealtimeMarket,
                props.bucketShared,
                props.bucketBg,
                props.bucketUser + props.bucketRealtimeMarket + props.bucketShared + props.bucketBg,
                props.bucketCapacity,
            )
        }
    }

    /**
     * 버킷 config — **`refillIntervally` LOCK** (ADR 0116 §2-C · R2 refill 모델 정합).
     *
     * 근거 (refillGreedy 금지):
     *   - `refillGreedy(N, 1s)` = 1초당 N토큰을 100ms당 1토큰 step으로 회복하되 **idle 후 capacity 만큼 burst 즉시 허용**.
     *     → KIS server-side 가 더 엄격한 알고리즘(sub-second 분해 — **공식 미명시 "추정"**, external-api-policy.md §2-1)이면
     *       0.0초 burst N건이 EGW00201 위반 (보고서 2026-05-22 §2-3·§4-5 evidence).
     *   - `refillIntervally(N, 1s)` = **1초 경계마다 N토큰 일괄 충전 — 윈도 중간 부분 회복 0** → 한 초 안 균등 발사 강제.
     *     capacity = refill 로 idle 축적 burst 0 (KIS 10/s 정확히 맞추되 burst window 마진 0 — 보수적).
     *   - sub-second 추가 보수화는 caller-side 페이지 간 sleep(CandleRefreshService H1 150ms)이 본질 — 토큰 100ms 분해는 over-engineering (ADR 0116 §2-C).
     */
    private fun bucketConfigFor(
        capacity: Long,
        refill: Long,
    ): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit { limit ->
                limit
                    .capacity(capacity)
                    .refillIntervally(refill, Duration.ofSeconds(props.bucketRefillPeriodSeconds))
            }.build()

    private fun buildBucket(
        keySuffix: String,
        capacity: Long,
    ): BucketProxy = proxyManager.builder().build(props.bucketKey + keySuffix) { bucketConfigFor(capacity, capacity) }

    /** USER 버킷 (capacity=bucketUser) — USER_PATH 1차. lazy 초기화 (ProxyManager Bean 부팅 후 가용). */
    private val userBucket: BucketProxy by lazy { buildBucket(USER_KEY_SUFFIX, props.bucketUser) }

    /** REALTIME 버킷 — ACTIVE 전략 degraded 시세 보강 전용. */
    private val realtimeBucket: BucketProxy by lazy { buildBucket(REALTIME_KEY_SUFFIX, props.bucketRealtimeMarket) }

    /** SHARED 버킷 (capacity=bucketShared) — INGESTION 1차 + USER_PATH fallback 2단. */
    private val sharedBucket: BucketProxy by lazy { buildBucket(SHARED_KEY_SUFFIX, props.bucketShared) }

    /** BG 버킷 (capacity=bucketBg) — BACKFILL·INGESTION_LOW 1차 + 상위 priority fallback 말단. */
    private val bgBucket: BucketProxy by lazy { buildBucket(BG_KEY_SUFFIX, props.bucketBg) }

    /** 단일 :global 버킷 (capacity=bucketCapacity) — priorityBucketsActive=false 회귀 path (Phase 1). */
    private val globalBucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey) { bucketConfigFor(props.bucketCapacity, props.bucketCapacity) }
    }

    /**
     * priority → 접근 가능 버킷 리스트 (역방향 fallback · ADR 0116 §2-B).
     *
     * 상위 priority일수록 더 많은 버킷 접근 (when exhaustive — 신규 priority 추가 시 빌드 게이트):
     *   - USER_PATH  → [USER, SHARED, BG]  (주문 절대 안 막힘)
     *   - INGESTION  → [SHARED, BG]
     *   - BACKFILL / INGESTION_LOW → [BG]  (상위 미침범)
     *
     * 진보 페르소나 (ADR 0087): 💡 버킷 리스트 순회로 2단 reserve를 N단 fallback으로 일반화.
     *
     * 각 원소는 `(metric 라벨, BucketProxy)` 쌍 — ADR 0116 §2-E 버킷별 사용률 metric 라벨링.
     */
    private fun bucketsFor(priority: KisCallPriority): List<Pair<String, BucketProxy>> =
        if (realtimeBucketActive) {
            when (priority) {
                KisCallPriority.USER_PATH ->
                    listOf(
                        BUCKET_USER to userBucket,
                        BUCKET_REALTIME to realtimeBucket,
                        BUCKET_SHARED to sharedBucket,
                        BUCKET_BG to bgBucket,
                    )
                KisCallPriority.REALTIME_MARKET ->
                    listOf(BUCKET_REALTIME to realtimeBucket, BUCKET_SHARED to sharedBucket, BUCKET_BG to bgBucket)
                KisCallPriority.INGESTION -> listOf(BUCKET_SHARED to sharedBucket, BUCKET_BG to bgBucket)
                KisCallPriority.BACKFILL, KisCallPriority.INGESTION_LOW -> listOf(BUCKET_BG to bgBucket)
            }
        } else {
            when (priority) {
                KisCallPriority.USER_PATH ->
                    listOf(BUCKET_USER to userBucket, BUCKET_SHARED to sharedBucket, BUCKET_BG to bgBucket)
                KisCallPriority.REALTIME_MARKET, KisCallPriority.INGESTION ->
                    listOf(BUCKET_SHARED to sharedBucket, BUCKET_BG to bgBucket)
                KisCallPriority.BACKFILL, KisCallPriority.INGESTION_LOW -> listOf(BUCKET_BG to bgBucket)
            }
        }

    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking polling).
     *
     * 라우팅 알고리즘 (ADR 0116 §2-B):
     *   1. priority별 접근 가능 버킷 리스트를 순회하며 `tryConsume(1)` → 첫 성공 즉시 return
     *   2. 모든 접근 가능 버킷 소진 → polling interval(50ms) sleep 후 재시도
     *   3. timeoutMillis 누적 초과 → throw [KisRateLimitTimeoutException]
     *
     * 역방향 fallback으로 하위 priority(BACKFILL·INGESTION_LOW)는 BG만 접근 → 상위(USER_PATH·INGESTION) 버킷 보존.
     *
     * Phase 3 후속 최적화 후보:
     *   - BucketProxy `asScheduler().tryConsume(1, maxWait, scheduler)` 합류 (ScheduledExecutorService 의존)
     */
    override fun acquire(
        priority: KisCallPriority,
        timeoutMillis: Long,
        trId: String?,
    ) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        var attempts = 0

        while (true) {
            attempts++
            if (tryConsumeRouted(priority)) {
                if (attempts > 1) {
                    log.debug(
                        "[KisRateLimit] acquire OK after polling — priority={} attempts={} elapsedMs={}",
                        priority,
                        attempts,
                        (System.nanoTime() - (deadlineNanos - timeoutMillis * 1_000_000L)) / 1_000_000L,
                    )
                }
                recordKisCall(trId, priority, RESULT_OK)
                return
            }
            // token 부족 → polling 또는 timeout
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                log.warn(
                    "[KisRateLimit] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={} priorityBucketsActive={} trId={}",
                    priority,
                    timeoutMillis,
                    attempts,
                    props.bucketKey,
                    priorityBucketsActive,
                    trId ?: TR_ID_UNKNOWN,
                )
                recordAcquireTimeout(priority)
                recordKisCall(trId, priority, RESULT_TIMEOUT)
                throw KisRateLimitTimeoutException(priority, timeoutMillis)
            }
            val sleepMillis = props.acquirePollIntervalMillis.coerceAtMost(remainingNanos / 1_000_000L)
            if (sleepMillis > 0L) {
                // TODO: virtual thread 도입 시 LockSupport.parkNanos 또는 BucketProxy.asScheduler() 합류 — ADR 0037 Phase 3 후속
                // 현 platform thread 환경에서 Thread.sleep은 안전하나, JDK 21 virtual thread 합류 시
                // pinning risk 회피 위해 java.util.concurrent.locks.LockSupport.parkNanos(nanos) 또는
                // Bucket4j의 BucketProxy.asScheduler() (CompletableFuture 기반 non-blocking) 전환 의무.
                Thread.sleep(sleepMillis)
            }
        }
    }

    /**
     * priority 라우팅 단발 소비 (non-blocking) — 접근 가능 버킷 리스트 순회 (ADR 0116 §2-B).
     *
     * - priorityBucketsActive=true: [bucketsFor] 순회하며 첫 성공 버킷에서 소비 (역방향 fallback).
     * - priorityBucketsActive=false: 단일 :global 버킷만 소비 (Phase 1 회귀).
     *
     * 진보 페르소나 (ADR 0087): 💡 `when` exhaustive 버킷 매핑 + `firstOrNull` 단축평가 순회로 N단 fallback 표현.
     *
     * 소비 성공 버킷 라벨을 [recordBucketConsumed] + [lastConsumedBucket] 에 기록 (ADR 0116 §2-E 관측).
     */
    private fun tryConsumeRouted(priority: KisCallPriority): Boolean {
        if (!priorityBucketsActive) {
            val ok = globalBucket.tryConsume(1L)
            if (ok) onBucketConsumed(BUCKET_GLOBAL)
            return ok
        }
        // 역방향 fallback 순회 — 첫 성공 버킷에서 소비 + 라벨 기록 (단축평가)
        val consumed = bucketsFor(priority).firstOrNull { (_, bucket) -> bucket.tryConsume(1L) }
        if (consumed != null) {
            onBucketConsumed(consumed.first)
            return true
        }
        return false
    }

    /** 버킷 소비 성공 시 — last_bucket 추적 갱신 + Micrometer counter increment (graceful · ADR 0116 §2-E). */
    private fun onBucketConsumed(bucketLabel: String) {
        lastConsumedBucket.set(bucketLabel)
        recordBucketConsumed(bucketLabel)
    }

    /**
     * EGW00201 / HTTP 429 응답 시그널 — 통계 누적 + SystemEvent emit (P0 hotfix).
     *
     * ### 진단 PR #639 P0 hotfix
     * - 기존: AtomicLong + WARN log만 → 사용자 알림센터 0건
     * - 변경: SystemEventLogger.emit(KIS_RATELIMIT, WARN) 합류 — `/system` 페이지 가시화
     * - aggregator 5분 LOCK — spam 회피 (EGW00201 burst 시 1건만 발사)
     *
     * Phase 3 합류 시 Resilience4j Circuit Breaker `onError()` 위임 → 5분 윈도 50% 임계 도달 시 OPEN.
     */
    override fun onTpsExceeded() {
        val cumulative = tpsExceededCount.incrementAndGet()
        // 윈도 카운터는 emit skip 여부와 무관하게 매 신호마다 누적 (emit 시점 스냅샷+리셋).
        windowTpsCount.incrementAndGet()
        val lastBucket = lastConsumedBucket.get()
        log.warn(
            "[KisRateLimit] TPS exceeded signal — cumulative={} lastBucket={} (Phase 3 Circuit Breaker 합류 대기)",
            cumulative,
            lastBucket,
        )
        recordTpsExceeded(lastBucket)
        emitSystemEventIfThresholdReached(cumulative, lastBucket)
    }

    /**
     * SystemEvent emit aggregator — 5분 윈도 1회 LOCK (spam 회피) + 윈도 건수 기반 severity 차등.
     *
     * 알고리즘 (lock-free CAS):
     *   1. 직전 emit 시각과 현재 시각 비교
     *   2. 5분 미경과 → skip (윈도 카운터는 [onTpsExceeded]에서 이미 누적 — 다음 emit에 반영)
     *   3. 5분 경과 → AtomicReference CAS 갱신 → 윈도 카운터 스냅샷+리셋 → severity 차등 emit
     *   4. CAS 실패 (다른 thread가 동시에 갱신) → skip (단일 emit 보장)
     *
     * severity 차등 (2026-06-10 노이즈 진단 hotfix):
     *   - 윈도 건수 < threshold → INFO (dispatcher가 toast/Slack 미발송, 알림센터 기록만)
     *   - 윈도 건수 ≥ threshold → WARN (현행 알람 경로)
     */
    private fun emitSystemEventIfThresholdReached(
        cumulativeCount: Long,
        lastBucket: String,
    ) {
        val logger = systemEventLogger ?: return
        val now = Instant.now()
        val prev = lastEmittedAt.get()
        if (Duration.between(prev, now) < EMIT_AGGREGATOR_WINDOW) {
            return // aggregator skip — 윈도 카운터는 누적 유지
        }
        if (!lastEmittedAt.compareAndSet(prev, now)) {
            return // race — 다른 thread 가 emit (단일 보장)
        }

        // 이번 윈도 발생 건수 스냅샷 + 리셋 (lock-free).
        val windowCount = windowTpsCount.getAndSet(0L)
        val severity =
            if (windowCount >= props.tpsExceededWarnThreshold) {
                SystemEventSeverity.WARN
            } else {
                SystemEventSeverity.INFO
            }

        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.KIS_RATELIMIT,
                    severity = severity,
                    actor = ACTOR,
                    action = ACTION_TPS_EXCEEDED,
                    userId = null, // system-wide broadcast
                    correlationId = null,
                    detailJson = buildDetailJson(windowCount, cumulativeCount, lastBucket),
                ),
            )
            log.warn(
                "[KisRateLimit] SystemEvent KIS_RATELIMIT emit — windowCount={} severity={} cumulative={} window=5min",
                windowCount,
                severity,
                cumulativeCount,
            )
        }.onFailure { e ->
            log.error(
                "[KisRateLimit] SystemEvent emit 실패 — windowCount={} err={}",
                windowCount,
                e.message,
            )
        }
    }

    /**
     * SystemEvent detailJson 합성 — windowCount 1차 노출 + bucketKey 오표기 정정 (2026-06-10).
     *
     * bucketKey 정정: 3-tier 활성 시 legacy `:global` base key 대신 실질 모드를 표기
     * (`mode` + lastBucket suffix). priorityBucketsActive=false면 단일 :global base key 그대로.
     */
    private fun buildDetailJson(
        windowCount: Long,
        cumulativeCount: Long,
        lastBucket: String,
    ): String {
        val effectiveBucketKey =
            if (priorityBucketsActive) {
                "${props.bucketKey}:$lastBucket"
            } else {
                props.bucketKey
            }
        return """{"windowCount":$windowCount,"cumulativeCount":$cumulativeCount,""" +
            """"bucketKey":"$effectiveBucketKey","priorityBucketsActive":$priorityBucketsActive,""" +
            """"lastBucket":"$lastBucket","aggregatorWindowMinutes":5}"""
    }

    /**
     * 정상 응답 시그널 (Phase 1: log debug + 카운터만 — fail rate 분모 누적).
     */
    override fun onSuccess() {
        val count = successCount.incrementAndGet()
        if (count % 100L == 0L) {
            log.debug(
                "[KisRateLimit] success milestone — count={} tpsExceeded={}",
                count,
                tpsExceededCount.get(),
            )
        }
    }

    // --- Micrometer 관측 helper (ADR 0116 §2-E · R2) ---
    //
    // 진보 페르소나 (ADR 0087):
    //   - ✨ MeterRegistry nullable graceful — null 시 no-op (테스트·Actuator 미존재 fixture 안전)
    //   - 🔄 Counter.builder + register 매 호출은 Micrometer 내부 tag-set 캐시로 안전 (PersistenceMetrics 동일 패턴)
    //   - 💡 metric record 실패가 acquire/주문 path 를 절대 죽이지 않음 — runCatching wrap

    /** 버킷별 토큰 소진(사용률) counter — `kis_ratelimit_bucket_consumed_total{bucket}` (ADR 0116 §2-E). */
    private fun recordBucketConsumed(bucketLabel: String) {
        val registry = meterRegistry ?: return
        runCatching {
            Counter
                .builder(METRIC_BUCKET_CONSUMED)
                .description("KIS rate limit 버킷별 토큰 소진 (ADR 0116 §2-E 사용률)")
                .tags(Tags.of("bucket", bucketLabel))
                .register(registry)
                .increment()
        }.onFailure { e -> log.trace("recordBucketConsumed skip: bucket={} err={}", bucketLabel, e.message) }
    }

    /** priority별 acquire timeout counter — `kis_ratelimit_acquire_timeout_total{priority}` (ADR 0116 §2-E 굶음 식별). */
    private fun recordAcquireTimeout(priority: KisCallPriority) {
        val registry = meterRegistry ?: return
        runCatching {
            Counter
                .builder(METRIC_ACQUIRE_TIMEOUT)
                .description("KIS rate limit acquire timeout (어느 priority 가 굶었는지 · ADR 0116 §2-E)")
                .tags(Tags.of("priority", priority.name))
                .register(registry)
                .increment()
        }.onFailure { e -> log.trace("recordAcquireTimeout skip: priority={} err={}", priority, e.message) }
    }

    /** EGW00201 발생 시 직전 소진 버킷 counter — `kis_ratelimit_tps_exceeded_total{last_bucket}` (ADR 0116 §2-E). */
    private fun recordTpsExceeded(lastBucket: String) {
        val registry = meterRegistry ?: return
        runCatching {
            Counter
                .builder(METRIC_TPS_EXCEEDED)
                .description("KIS EGW00201 발생 시 직전 소진 버킷 추적 (ADR 0116 §2-E)")
                .tags(Tags.of("last_bucket", lastBucket))
                .register(registry)
                .increment()
        }.onFailure { e -> log.trace("recordTpsExceeded skip: lastBucket={} err={}", lastBucket, e.message) }
    }

    /**
     * tr_id별 KIS 호출량 counter — `kis_call_total{tr_id, priority, result}` (ADR 0116 §2-E 연장).
     *
     * 어느 KIS API(tr_id)가 TPS를 먹는지·어느 tr_id가 timeout 굶는지 식별.
     * trId null → `unknown` 라벨. cardinality: tr_id는 유한 KIS 상수 집합 + priority 5 + result 2 → 안전.
     */
    private fun recordKisCall(
        trId: String?,
        priority: KisCallPriority,
        result: String,
    ) {
        val registry = meterRegistry ?: return
        runCatching {
            Counter
                .builder(METRIC_KIS_CALL)
                .description("KIS API tr_id별 호출량 (ADR 0116 §2-E 연장 — 어느 API가 TPS 점유)")
                .tags(
                    Tags.of(
                        "tr_id",
                        trId ?: TR_ID_UNKNOWN,
                        "priority",
                        priority.name,
                        "result",
                        result,
                    ),
                ).register(registry)
                .increment()
        }.onFailure { e -> log.trace("recordKisCall skip: trId={} err={}", trId, e.message) }
    }

    /** 테스트·모니터링 진입점 (Phase 3 Circuit Breaker에서 권위 위임) */
    internal fun snapshotTpsExceeded(): Long = tpsExceededCount.get()

    internal fun snapshotSuccess(): Long = successCount.get()

    /** 테스트 진입점 — 현재 윈도 미emit 누적 건수 (emit 시 0으로 리셋). */
    internal fun snapshotWindowTpsCount(): Long = windowTpsCount.get()

    /** 테스트 진입점 — 직전 소진 버킷 라벨 (ADR 0116 §2-E last_bucket 검증). */
    internal fun snapshotLastConsumedBucket(): String = lastConsumedBucket.get()
}
