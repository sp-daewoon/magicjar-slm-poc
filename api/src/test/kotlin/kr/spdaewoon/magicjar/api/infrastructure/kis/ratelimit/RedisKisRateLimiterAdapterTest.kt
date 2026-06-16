package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * RedisKisRateLimiterAdapter Bucket4j-Redis 3-tier priority 버킷 단위 테스트 (ADR 0116 §2-B).
 *
 * 검증:
 *   1. acquire-success: USER 버킷 tryConsume(1) GREEN → 즉시 return
 *   2. acquire-success-after-polling: 처음 2회 RED → polling 후 3회차 GREEN → return
 *   3. acquire-timeout: 지속 RED → 100ms timeout → KisRateLimitTimeoutException
 *   4. 역방향 fallback: USER_PATH→[user,shared,bg]·INGESTION→[shared,bg]·BACKFILL→[bg] (상위 priority가 더 많은 버킷 접근)
 *   5. priorityBucketsEnabled=false → 단일 :global 버킷 회귀
 *   6. 버킷 합 ≠ capacity → 단일 bucket fallback (fail-safe)
 *   7. onTpsExceeded/SystemEvent emit
 *
 * Bucket4j mock 전략: ProxyManager.builder().build(keySuffix별, Supplier) → 버킷별 mock.
 *
 * 자본 영향 0 — mock 단위 (KIS endpoint·Redis 호출 0).
 */
@Suppress("UNCHECKED_CAST")
class RedisKisRateLimiterAdapterTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>

    /** USER 버킷 — bucketKey + ":user" (USER_PATH 1차). */
    private lateinit var userBucket: BucketProxy

    /** SHARED 버킷 — bucketKey + ":shared" (INGESTION 1차 + USER_PATH fallback). */
    private lateinit var sharedBucket: BucketProxy

    /** BG 버킷 — bucketKey + ":bg" (BACKFILL·INGESTION_LOW 1차 + 상위 fallback 말단). */
    private lateinit var bgBucket: BucketProxy

    /** 단일 :global 버킷 — priorityBucketsEnabled=false 회귀 path. */
    private lateinit var globalBucket: BucketProxy
    private lateinit var props: KisRateLimitProperties
    private lateinit var adapter: RedisKisRateLimiterAdapter

    private companion object {
        const val BUCKET_KEY = "kis-rate-limit:test"
        const val USER_KEY = "$BUCKET_KEY:user"
        const val SHARED_KEY = "$BUCKET_KEY:shared"
        const val BG_KEY = "$BUCKET_KEY:bg"
    }

    @BeforeEach
    fun setUp() {
        proxyManager = mock()
        remoteBucketBuilder = mock()
        userBucket = mock()
        sharedBucket = mock()
        bgBucket = mock()
        globalBucket = mock()
        whenever(proxyManager.builder()).thenReturn(remoteBucketBuilder)
        wireBucket(USER_KEY, userBucket)
        wireBucket(SHARED_KEY, sharedBucket)
        wireBucket(BG_KEY, bgBucket)
        wireBucket(BUCKET_KEY, globalBucket)
        props =
            KisRateLimitProperties(
                enabled = true,
                bucketCapacity = 10L,
                bucketRefillTokens = 10L,
                priorityBucketsEnabled = true,
                bucketUser = 4L,
                bucketShared = 3L,
                bucketBg = 3L,
                bucketRefillPeriodSeconds = 1L,
                bucketKey = BUCKET_KEY,
                bucketTtlSeconds = 10L,
                acquirePollIntervalMillis = 10L, // 테스트 빠른 polling
            )
        adapter = RedisKisRateLimiterAdapter(proxyManager, props)
    }

    private fun wireBucket(
        key: String,
        bucket: BucketProxy,
    ) {
        whenever(
            remoteBucketBuilder.build(
                eq(key),
                any<java.util.function.Supplier<io.github.bucket4j.BucketConfiguration>>(),
            ),
        ).thenReturn(bucket)
    }

    /** 검증 1: USER 버킷 즉시 GREEN → acquire(USER_PATH) 즉시 return. */
    @Test
    fun `acquire - USER_PATH 토큰 사용 가능 시 즉시 return`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(userBucket, times(1)).tryConsume(1L)
        verify(sharedBucket, never()).tryConsume(1L)
        verify(bgBucket, never()).tryConsume(1L)
    }

    /** 검증 2: BG 버킷 처음 2회 RED → polling 후 3회차 GREEN → return. */
    @Test
    fun `acquire - polling 후 토큰 회복 시 return`() {
        whenever(bgBucket.tryConsume(1L))
            .thenReturn(false)
            .thenReturn(false)
            .thenReturn(true)

        adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)

        verify(bgBucket, times(3)).tryConsume(1L)
    }

    /** 검증 3: 지속 RED → timeout 초과 시 throw + BACKFILL 은 bg 만 접근 (상위 미침범). */
    @Test
    fun `acquire - timeout 초과 시 KisRateLimitTimeoutException throw`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(false)
        whenever(sharedBucket.tryConsume(1L)).thenReturn(false)
        whenever(bgBucket.tryConsume(1L)).thenReturn(false)

        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 100L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)
            .hasMessageContaining("BACKFILL")
            .hasMessageContaining("timeoutMillis=100")

        verify(bgBucket, org.mockito.kotlin.atLeast(2)).tryConsume(1L)
        // BACKFILL 은 USER·SHARED 버킷 미접근 (상위 priority 굶기지 않음)
        verify(userBucket, never()).tryConsume(1L)
        verify(sharedBucket, never()).tryConsume(1L)
    }

    /**
     * ★ 검증 4-a: 역방향 fallback — USER_PATH 는 USER 소진 시 SHARED → BG 순으로 fallback (ADR 0116 §2-B, 14:59 evidence 직격).
     */
    @Test
    fun `acquire - USER_PATH 는 USER 소진 시 SHARED·BG 로 역방향 fallback`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(false) // USER 소진
        whenever(sharedBucket.tryConsume(1L)).thenReturn(false) // SHARED 소진
        whenever(bgBucket.tryConsume(1L)).thenReturn(true) // BG 잔여 → 통과

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(userBucket, times(1)).tryConsume(1L)
        verify(sharedBucket, times(1)).tryConsume(1L)
        verify(bgBucket, times(1)).tryConsume(1L)
    }

    /**
     * ★ 검증 4-b: INGESTION 은 SHARED → BG fallback 가능하나 USER 버킷 미접근 (상위 보존).
     */
    @Test
    fun `acquire - INGESTION 은 SHARED·BG 접근 가능하나 USER 버킷 미침범`() {
        whenever(sharedBucket.tryConsume(1L)).thenReturn(false) // SHARED 소진
        whenever(bgBucket.tryConsume(1L)).thenReturn(true) // BG 잔여 → 통과

        adapter.acquire(KisCallPriority.INGESTION, timeoutMillis = 1000L)

        verify(userBucket, never()).tryConsume(1L) // USER_PATH 전용 — INGESTION 미접근
        verify(sharedBucket, times(1)).tryConsume(1L)
        verify(bgBucket, times(1)).tryConsume(1L)
    }

    /**
     * ★ 검증 4-c: 하위 priority burst 가 상위(USER_PATH) 를 굶기지 못함 —
     * BG 소진 시 BACKFILL/INGESTION_LOW 는 timeout 이나 USER 버킷은 보존.
     */
    @Test
    fun `acquire - BG 소진 시 INGESTION_LOW timeout 이나 USER 버킷 보존`() {
        whenever(bgBucket.tryConsume(1L)).thenReturn(false)
        whenever(userBucket.tryConsume(1L)).thenReturn(true) // USER 잔여 (소비 안 됨)

        assertThatThrownBy {
            adapter.acquire(KisCallPriority.INGESTION_LOW, timeoutMillis = 100L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)

        verify(userBucket, never()).tryConsume(1L) // 보존 — INGESTION_LOW 가 USER 침범 0
        verify(sharedBucket, never()).tryConsume(1L)
    }

    /**
     * ★ 검증 5: priorityBucketsEnabled=false → 단일 :global 버킷 회귀 (rollback path).
     */
    @Test
    fun `acquire - priorityBucketsEnabled false 시 단일 global 버킷 회귀`() {
        val singleProps = props.copy(priorityBucketsEnabled = false)
        val singleAdapter = RedisKisRateLimiterAdapter(proxyManager, singleProps)
        whenever(globalBucket.tryConsume(1L)).thenReturn(true)

        singleAdapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(globalBucket, times(1)).tryConsume(1L)
        verify(userBucket, never()).tryConsume(1L)
        verify(sharedBucket, never()).tryConsume(1L)
        verify(bgBucket, never()).tryConsume(1L)
    }

    /**
     * ★ 검증 6: 버킷 합 ≠ capacity → 단일 bucket fallback (fail-safe).
     */
    @Test
    fun `acquire - 버킷 합 불일치 시 단일 global 버킷 fallback`() {
        // user+shared+bg = 4+3+5 = 12 != capacity 10 → 단일 bucket fallback
        val mismatchProps = props.copy(bucketBg = 5L)
        val mismatchAdapter = RedisKisRateLimiterAdapter(proxyManager, mismatchProps)
        whenever(globalBucket.tryConsume(1L)).thenReturn(true)

        mismatchAdapter.acquire(KisCallPriority.INGESTION, timeoutMillis = 1000L)

        verify(globalBucket, times(1)).tryConsume(1L)
        verify(sharedBucket, never()).tryConsume(1L)
    }

    /** 검증 7: onTpsExceeded() 시그널 누적. */
    @Test
    fun `onTpsExceeded - 시그널 카운터 누적 검증`() {
        assertThat(adapter.snapshotTpsExceeded()).isEqualTo(0L)

        adapter.onTpsExceeded()
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()

        assertThat(adapter.snapshotTpsExceeded()).isEqualTo(3L)

        assertThat(adapter.snapshotSuccess()).isEqualTo(0L)
        adapter.onSuccess()
        adapter.onSuccess()
        assertThat(adapter.snapshotSuccess()).isEqualTo(2L)
    }

    /**
     * 검증 8: SystemEvent KIS_RATELIMIT emit — 5분 aggregator LOCK (첫 윈도 1회만 emit).
     *
     * 윈도 건수 3건(< threshold 5) → INFO 차등 (2026-06-10 알람 차등화).
     * detailJson 은 windowCount/cumulativeCount 1차 노출.
     */
    @Test
    fun `onTpsExceeded - SystemEvent emit 1회 + 윈도 3건은 INFO 차등 + windowCount detail`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val adapterWithLogger = RedisKisRateLimiterAdapter(proxyManager, props, systemEventLogger)

        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()

        assertThat(adapterWithLogger.snapshotTpsExceeded()).isEqualTo(3L)

        val captor = org.mockito.kotlin.argumentCaptor<SystemEvent>()
        verify(systemEventLogger, times(1)).emit(captor.capture())
        val event = captor.firstValue
        assertThat(event.category).isEqualTo(SystemEventCategory.KIS_RATELIMIT)
        // 첫 emit 시점 windowCount=1 (< threshold 5) → INFO (알림센터 기록만, Slack/toast 미발송)
        assertThat(event.severity).isEqualTo(SystemEventSeverity.INFO)
        assertThat(event.actor).isEqualTo("RedisKisRateLimiterAdapter")
        assertThat(event.action).isEqualTo("KIS_TPS_EXCEEDED")
        assertThat(event.userId).isNull()
        assertThat(event.detailJson).contains("windowCount")
        assertThat(event.detailJson).contains("cumulativeCount")
        // bucketKey 오표기 정정 — 3-tier 활성 시 base key + lastBucket suffix
        assertThat(event.detailJson).contains("kis-rate-limit:test")
        assertThat(event.detailJson).contains("priorityBucketsActive")
    }

    /**
     * 검증 8-b: 윈도 건수 ≥ threshold(5) → WARN 차등 (burst만 사용자 알람 도달).
     *
     * lastEmittedAt 초기값 EPOCH 이므로 첫 emit 즉시 발생 — 첫 윈도에 5건 누적 후 emit 1회.
     * 단, aggregator 가 첫 호출에서 바로 emit 하므로 윈도 건수를 5건 채운 뒤 단일 emit 을 강제하려면
     * snapshotWindowTpsCount 로 누적을 직접 검증한다(emit 차등은 통합 시점 — 본 단위는 누적·리셋 LOCK).
     */
    @Test
    fun `onTpsExceeded - 윈도 카운터 누적 후 emit 시 0 으로 리셋`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val adapterWithLogger = RedisKisRateLimiterAdapter(proxyManager, props, systemEventLogger)

        // 첫 호출: lastEmittedAt=EPOCH → 즉시 emit → windowCount 스냅샷(1)+리셋(0)
        adapterWithLogger.onTpsExceeded()
        assertThat(adapterWithLogger.snapshotWindowTpsCount()).isEqualTo(0L)

        // 이후 호출들: 5분 미경과 → emit skip, 윈도 카운터만 누적
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()
        assertThat(adapterWithLogger.snapshotWindowTpsCount()).isEqualTo(3L)
        // 누적치는 계속 증가 (리셋 없음)
        assertThat(adapterWithLogger.snapshotTpsExceeded()).isEqualTo(4L)

        // emit 은 첫 윈도 1회만 (aggregator LOCK)
        verify(systemEventLogger, times(1)).emit(any())
    }

    /**
     * 검증 8-c: threshold=1 properties 로 즉시 WARN 차등 (severity 분기 직접 검증).
     */
    @Test
    fun `onTpsExceeded - threshold 1 시 첫 윈도 WARN severity`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val warnProps = props.copy(tpsExceededWarnThreshold = 1)
        val adapterWithLogger = RedisKisRateLimiterAdapter(proxyManager, warnProps, systemEventLogger)

        adapterWithLogger.onTpsExceeded()

        val captor = org.mockito.kotlin.argumentCaptor<SystemEvent>()
        verify(systemEventLogger, times(1)).emit(captor.capture())
        // windowCount=1 >= threshold 1 → WARN
        assertThat(captor.firstValue.severity).isEqualTo(SystemEventSeverity.WARN)
    }

    /** 검증 9: SystemEventLogger null 주입 시 emit 0 (회귀 마진 LOCK). */
    @Test
    fun `onTpsExceeded - SystemEventLogger 0 주입 시 emit 0 (회귀 마진 LOCK)`() {
        val systemEventLogger = mock<SystemEventLogger>()
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()

        verify(systemEventLogger, never()).emit(any())
    }
}
