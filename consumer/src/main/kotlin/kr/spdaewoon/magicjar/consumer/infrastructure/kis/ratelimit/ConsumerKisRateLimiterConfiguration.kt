package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.micrometer.core.instrument.MeterRegistry
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * consumer 모듈 KIS Rate Limit Bean wiring (spec PR #564 §2-3 옵션 a 정합).
 *
 * Phase 2 dispatch LOCK:
 *   - api 모듈 [kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit.KisRateLimiterConfiguration]와 동일 패턴
 *   - 동일 bucket key `kis-rate-limit:global` → Redis-level multi-JVM share (api·consumer ApplicationContext 분리 정합)
 *   - magicjar.kis.rate-limiter.enabled=false 시 Bean 0 → caller는 sleep fallback (회귀 마진 LOCK)
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 메커니즘
 *   - ADR 0038 — Redis 인프라 재사용 (spring.data.redis.host/port 재사용)
 *
 * 옵션 a vs 옵션 b 결정 (spec §2-3):
 *   - 옵션 a 채택 — api·consumer 각자 Bean 발행 (DRY 위반은 Phase 3 persistence-shared 이관 후속 backlog)
 *   - 옵션 b (persistence-shared 이관) 거부 사유 — 패키지명/import 일괄 변경 영향 매트릭스 크고 본 dispatch 범위 외
 */
@Configuration
@EnableConfigurationProperties(ConsumerKisRateLimitProperties::class)
@ConditionalOnProperty(
    prefix = "magicjar.kis.rate-limiter",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ConsumerKisRateLimiterConfiguration {
    /**
     * Bucket4j 전용 Lettuce RedisClient (Spring Data Redis와 별도 발행 — api 모듈과 동일 패턴).
     * destroyMethod="shutdown" 으로 graceful close 보장 (ADR 0045 SIGTERM 정합).
     */
    @Bean(destroyMethod = "shutdown")
    fun consumerKisRateLimitRedisClient(
        @Value("\${spring.data.redis.host:localhost}") host: String,
        @Value("\${spring.data.redis.port:6379}") port: Int,
    ): RedisClient {
        val uri =
            RedisURI
                .builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(2))
                .build()
        return RedisClient.create(uri)
    }

    /** Bucket4j 단일 connection (long-lived, share 안전). */
    @Bean(destroyMethod = "close")
    fun consumerKisRateLimitRedisConnection(consumerKisRateLimitRedisClient: RedisClient): StatefulRedisConnection<String, ByteArray> {
        val codec: RedisCodec<String, ByteArray> = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
        return consumerKisRateLimitRedisClient.connect(codec)
    }

    /**
     * Bucket4j ProxyManager — 모든 bucket 발행 진입점.
     * ExpirationAfterWriteStrategy: refill 윈도(1s)의 10배 TTL 자동 갱신 → 미사용 시 자동 정리.
     */
    @Bean
    fun consumerKisRateLimitProxyManager(
        consumerKisRateLimitRedisConnection: StatefulRedisConnection<String, ByteArray>,
        props: ConsumerKisRateLimitProperties,
    ): ProxyManager<String> =
        LettuceBasedProxyManager
            .builderFor(consumerKisRateLimitRedisConnection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(props.bucketTtlSeconds),
                ),
            ).build()

    /**
     * KisRateLimiterPort 어댑터 Bean — domain port LOCK 정합.
     *
     * 호출자 (Phase 2 dispatch 본문):
     *   - KisRestCandleAdapter, KisRestPriceAdapter, KisBalanceAdapter, KisApprovalAdapter
     *   - KisOrderHttpAdapter, KisOrderInquireCcldHttpAdapter, KisOrderInquireDailyCcldHttpAdapter
     *   - KisReconcileBalanceAdapter, KisHashKeyAdapter
     */
    @Bean
    fun consumerKisRateLimiterPort(
        consumerKisRateLimitProxyManager: ProxyManager<String>,
        props: ConsumerKisRateLimitProperties,
        @Autowired(required = false) systemEventLogger: SystemEventLogger?,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): KisRateLimiterPort = ConsumerRedisKisRateLimiterAdapter(consumerKisRateLimitProxyManager, props, systemEventLogger, meterRegistry)
}
