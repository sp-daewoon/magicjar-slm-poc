package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * ConsumerRedisKisRateLimiterAdapter Bucket4j-Redis 3-tier priority 버킷 단위 테스트 (ADR 0116 §2-B).
 *
 * api 모듈 [kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit.RedisKisRateLimiterAdapter]와 동일 패턴.
 * consumer 모듈 자체 Bean wiring 단위 검증.
 *
 * 자본 영향 0 — mock 단위 (KIS endpoint·Redis 호출 0).
 */
@Suppress("UNCHECKED_CAST")
class ConsumerRedisKisRateLimiterAdapterTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>

    private lateinit var userBucket: BucketProxy
    private lateinit var sharedBucket: BucketProxy
    private lateinit var bgBucket: BucketProxy
    private lateinit var globalBucket: BucketProxy
    private lateinit var props: ConsumerKisRateLimitProperties
    private lateinit var adapter: ConsumerRedisKisRateLimiterAdapter

    private companion object {
        const val BUCKET_KEY = "kis-rate-limit:test-consumer"
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
            ConsumerKisRateLimitProperties(
                enabled = true,
                bucketCapacity = 10L,
                bucketRefillTokens = 10L,
                priorityBucketsEnabled = true,
                bucketUser = 4L,
                bucketShared = 3L,
                bucketBg = 3L,
                bucketKey = BUCKET_KEY,
                bucketTtlSeconds = 10L,
                acquirePollIntervalMillis = 10L,
            )
        adapter = ConsumerRedisKisRateLimiterAdapter(proxyManager, props)
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

    @Test
    fun `acquire - USER_PATH 토큰 사용 가능 시 즉시 return`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(true)
        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
        verify(userBucket, times(1)).tryConsume(1L)
    }

    @Test
    fun `acquire - polling 후 토큰 회복 시 return`() {
        whenever(bgBucket.tryConsume(1L))
            .thenReturn(false)
            .thenReturn(false)
            .thenReturn(true)
        adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)
        verify(bgBucket, times(3)).tryConsume(1L)
    }

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
        verify(userBucket, never()).tryConsume(1L)
        verify(sharedBucket, never()).tryConsume(1L)
    }

    /**
     * ★ 역방향 fallback (ADR 0116 §2-B, 14:59 evidence 직격):
     * USER 소진 시 USER_PATH 본주문은 SHARED → BG 로 fallback 통과 (consumer 본주문 핵심 경로).
     */
    @Test
    fun `acquire - USER_PATH 는 USER 소진 시 SHARED·BG 로 역방향 fallback`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(false)
        whenever(sharedBucket.tryConsume(1L)).thenReturn(false)
        whenever(bgBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(userBucket, times(1)).tryConsume(1L)
        verify(sharedBucket, times(1)).tryConsume(1L)
        verify(bgBucket, times(1)).tryConsume(1L)
    }

    /**
     * ★ INGESTION 은 SHARED·BG 접근 가능하나 USER 버킷 미침범 (주문 보존).
     */
    @Test
    fun `acquire - INGESTION 은 USER 버킷 미접근 (주문 보존)`() {
        whenever(sharedBucket.tryConsume(1L)).thenReturn(false)
        whenever(bgBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.INGESTION, timeoutMillis = 1000L)

        verify(userBucket, never()).tryConsume(1L)
        verify(sharedBucket, times(1)).tryConsume(1L)
        verify(bgBucket, times(1)).tryConsume(1L)
    }

    /**
     * ★ priorityBucketsEnabled=false → 단일 :global 버킷 회귀 (rollback path).
     */
    @Test
    fun `acquire - priorityBucketsEnabled false 시 단일 global 버킷 회귀`() {
        val singleProps = props.copy(priorityBucketsEnabled = false)
        val singleAdapter = ConsumerRedisKisRateLimiterAdapter(proxyManager, singleProps)
        whenever(globalBucket.tryConsume(1L)).thenReturn(true)

        singleAdapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(globalBucket, times(1)).tryConsume(1L)
        verify(userBucket, never()).tryConsume(1L)
    }

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
     * 진단 PR #639 P0 hotfix — SystemEvent KIS_RATELIMIT emit aggregator 검증.
     *
     * 2026-06-10 알람 차등화 — 윈도 3건(< threshold 5) → INFO + windowCount detail.
     */
    @Test
    fun `onTpsExceeded - SystemEvent emit 1회 + 윈도 3건 INFO + windowCount detail`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val adapterWithLogger = ConsumerRedisKisRateLimiterAdapter(proxyManager, props, systemEventLogger)

        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()

        assertThat(adapterWithLogger.snapshotTpsExceeded()).isEqualTo(3L)
        val captor = argumentCaptor<SystemEvent>()
        verify(systemEventLogger, times(1)).emit(captor.capture())
        val event = captor.firstValue
        assertThat(event.category).isEqualTo(SystemEventCategory.KIS_RATELIMIT)
        // 첫 emit windowCount=1 (< threshold 5) → INFO
        assertThat(event.severity).isEqualTo(SystemEventSeverity.INFO)
        assertThat(event.actor).isEqualTo("ConsumerRedisKisRateLimiterAdapter")
        assertThat(event.action).isEqualTo("KIS_TPS_EXCEEDED")
        assertThat(event.detailJson).contains("\"module\":\"consumer\"")
        assertThat(event.detailJson).contains("windowCount")
        assertThat(event.detailJson).contains("cumulativeCount")
        // emit skip 윈도 누적 검증
        assertThat(adapterWithLogger.snapshotWindowTpsCount()).isEqualTo(2L)
    }

    /** 윈도 건수 ≥ threshold → WARN 차등 (threshold=1 로 즉시 검증). */
    @Test
    fun `onTpsExceeded - threshold 1 시 첫 윈도 WARN severity`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val warnProps = props.copy(tpsExceededWarnThreshold = 1)
        val adapterWithLogger = ConsumerRedisKisRateLimiterAdapter(proxyManager, warnProps, systemEventLogger)

        adapterWithLogger.onTpsExceeded()

        val captor = argumentCaptor<SystemEvent>()
        verify(systemEventLogger, times(1)).emit(captor.capture())
        assertThat(captor.firstValue.severity).isEqualTo(SystemEventSeverity.WARN)
    }
}
