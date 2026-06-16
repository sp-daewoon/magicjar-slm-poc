package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ADR 0116 §2-E (R2) — KIS rate limit priority 버킷 관측 metric 단위 테스트.
 *
 * 검증 (Micrometer SimpleMeterRegistry 실측):
 *   1. `kis_ratelimit_bucket_consumed_total{bucket}` — 소비 성공 버킷 라벨별 누적
 *   2. 역방향 fallback 소비 시 fallback 버킷(bg) 라벨 기록
 *   3. `kis_ratelimit_acquire_timeout_total{priority}` — timeout priority 라벨 누적
 *   4. `kis_ratelimit_tps_exceeded_total{last_bucket}` — EGW00201 직전 소진 버킷 라벨
 *   5. priorityBucketsEnabled=false → bucket=global 라벨 기록
 *   6. MeterRegistry null 주입 시 graceful no-op (metric 0 — acquire 정상 동작)
 *
 * 자본 영향 0 — mock 단위 (KIS endpoint·Redis 호출 0).
 */
@Suppress("UNCHECKED_CAST")
class RedisKisRateLimiterAdapterMetricsTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>
    private lateinit var userBucket: BucketProxy
    private lateinit var sharedBucket: BucketProxy
    private lateinit var bgBucket: BucketProxy
    private lateinit var globalBucket: BucketProxy
    private lateinit var props: KisRateLimitProperties
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var adapter: RedisKisRateLimiterAdapter

    private companion object {
        const val BUCKET_KEY = "kis-rate-limit:metrics-test"
        const val USER_KEY = "$BUCKET_KEY:user"
        const val SHARED_KEY = "$BUCKET_KEY:shared"
        const val BG_KEY = "$BUCKET_KEY:bg"

        const val METRIC_CONSUMED = "kis.ratelimit.bucket.consumed"
        const val METRIC_TIMEOUT = "kis.ratelimit.acquire.timeout"
        const val METRIC_TPS = "kis.ratelimit.tps.exceeded"
        const val METRIC_KIS_CALL = "kis.call"
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
                acquirePollIntervalMillis = 10L,
            )
        registry = SimpleMeterRegistry()
        adapter = RedisKisRateLimiterAdapter(proxyManager, props, null, registry)
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

    private fun consumedCount(bucketLabel: String): Double =
        registry
            .find(METRIC_CONSUMED)
            .tag("bucket", bucketLabel)
            .counter()
            ?.count() ?: 0.0

    private fun timeoutCount(priority: String): Double =
        registry
            .find(METRIC_TIMEOUT)
            .tag("priority", priority)
            .counter()
            ?.count() ?: 0.0

    private fun tpsCount(lastBucket: String): Double =
        registry
            .find(METRIC_TPS)
            .tag("last_bucket", lastBucket)
            .counter()
            ?.count() ?: 0.0

    private fun kisCallCount(
        trId: String,
        priority: String,
        result: String,
    ): Double =
        registry
            .find(METRIC_KIS_CALL)
            .tag("tr_id", trId)
            .tag("priority", priority)
            .tag("result", result)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `bucket_consumed metric - USER 소비 성공 시 bucket=user 누적`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        assertThat(consumedCount("user")).isEqualTo(2.0)
        assertThat(consumedCount("shared")).isEqualTo(0.0)
        assertThat(adapter.snapshotLastConsumedBucket()).isEqualTo("user")
    }

    @Test
    fun `bucket_consumed metric - USER_PATH 역방향 fallback 시 bucket=bg 누적`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(false)
        whenever(sharedBucket.tryConsume(1L)).thenReturn(false)
        whenever(bgBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        assertThat(consumedCount("bg")).isEqualTo(1.0)
        assertThat(consumedCount("user")).isEqualTo(0.0)
        assertThat(adapter.snapshotLastConsumedBucket()).isEqualTo("bg")
    }

    @Test
    fun `acquire_timeout metric - 지속 소진 시 priority 라벨 누적`() {
        whenever(bgBucket.tryConsume(1L)).thenReturn(false)

        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 80L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)

        assertThat(timeoutCount("BACKFILL")).isEqualTo(1.0)
        assertThat(timeoutCount("USER_PATH")).isEqualTo(0.0)
    }

    @Test
    fun `tps_exceeded metric - EGW00201 시 직전 소진 버킷 last_bucket 라벨`() {
        // 먼저 BG 소비 성공 → lastConsumedBucket=bg
        whenever(bgBucket.tryConsume(1L)).thenReturn(true)
        adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)

        adapter.onTpsExceeded()

        assertThat(tpsCount("bg")).isEqualTo(1.0)
    }

    @Test
    fun `tps_exceeded metric - 소비 0 상태 EGW00201 시 last_bucket=none`() {
        adapter.onTpsExceeded()

        assertThat(tpsCount("none")).isEqualTo(1.0)
    }

    @Test
    fun `bucket_consumed metric - priorityBucketsEnabled false 시 bucket=global`() {
        val singleProps = props.copy(priorityBucketsEnabled = false)
        val singleAdapter = RedisKisRateLimiterAdapter(proxyManager, singleProps, null, registry)
        whenever(globalBucket.tryConsume(1L)).thenReturn(true)

        singleAdapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        assertThat(consumedCount("global")).isEqualTo(1.0)
    }

    @Test
    fun `kis_call metric - trId 전달 시 tr_id+priority+result=ok 라벨 누적 (ADR 0116 §2-E 연장)`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L, trId = "FHKST03010100")
        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L, trId = "FHKST03010100")

        assertThat(kisCallCount("FHKST03010100", "USER_PATH", "ok")).isEqualTo(2.0)
        assertThat(kisCallCount("FHKST03010100", "USER_PATH", "timeout")).isEqualTo(0.0)
    }

    @Test
    fun `kis_call metric - trId null 시 tr_id=unknown 라벨 (하위호환 2-arg)`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(true)

        // 기존 호출부(2-arg, trId default null) 무변경 컴파일 + unknown 라벨 집계
        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        assertThat(kisCallCount("unknown", "USER_PATH", "ok")).isEqualTo(1.0)
    }

    @Test
    fun `kis_call metric - timeout 시 result=timeout 라벨 + trId 보존`() {
        whenever(bgBucket.tryConsume(1L)).thenReturn(false)

        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 80L, trId = "CTCA0903R")
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)

        assertThat(kisCallCount("CTCA0903R", "BACKFILL", "timeout")).isEqualTo(1.0)
        assertThat(kisCallCount("CTCA0903R", "BACKFILL", "ok")).isEqualTo(0.0)
    }

    @Test
    fun `MeterRegistry null 주입 시 graceful no-op (acquire 정상)`() {
        val noMetricAdapter = RedisKisRateLimiterAdapter(proxyManager, props) // meterRegistry=null default
        whenever(userBucket.tryConsume(1L)).thenReturn(true)

        // metric 미주입이어도 acquire 정상 동작 (예외 0)
        noMetricAdapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        assertThat(noMetricAdapter.snapshotLastConsumedBucket()).isEqualTo("user")
    }
}
