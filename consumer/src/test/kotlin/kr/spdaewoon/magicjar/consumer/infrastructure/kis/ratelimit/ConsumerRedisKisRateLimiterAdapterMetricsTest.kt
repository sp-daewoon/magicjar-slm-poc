package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * ADR 0116 §2-E (R2) — consumer 모듈 본주문 경로 priority 버킷 관측 metric 검증.
 *
 * 본주문(USER_PATH)이 USER 버킷 소비 시 `kis_ratelimit_bucket_consumed_total{bucket=user}` 누적 +
 * EGW00201 시 `kis_ratelimit_tps_exceeded_total{last_bucket=user}` 추적 — 어느 버킷이 직격됐는지 식별.
 *
 * 자본 영향 0 — mock 단위 (KIS endpoint·Redis 호출 0).
 */
@Suppress("UNCHECKED_CAST")
class ConsumerRedisKisRateLimiterAdapterMetricsTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>
    private lateinit var userBucket: BucketProxy
    private lateinit var sharedBucket: BucketProxy
    private lateinit var bgBucket: BucketProxy
    private lateinit var globalBucket: BucketProxy
    private lateinit var props: ConsumerKisRateLimitProperties
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var adapter: ConsumerRedisKisRateLimiterAdapter

    private companion object {
        const val BUCKET_KEY = "kis-rate-limit:metrics-consumer"
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
        registry = SimpleMeterRegistry()
        adapter = ConsumerRedisKisRateLimiterAdapter(proxyManager, props, null, registry)
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
    fun `본주문 USER_PATH 소비 시 bucket=user 누적 + EGW00201 last_bucket=user`() {
        whenever(userBucket.tryConsume(1L)).thenReturn(true)

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
        assertThat(
            registry
                .find("kis.ratelimit.bucket.consumed")
                .tag("bucket", "user")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(adapter.snapshotLastConsumedBucket()).isEqualTo("user")

        adapter.onTpsExceeded()
        assertThat(
            registry
                .find("kis.ratelimit.tps.exceeded")
                .tag("last_bucket", "user")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
    }
}
