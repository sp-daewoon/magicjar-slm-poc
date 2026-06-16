package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

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
 * KIS Rate Limit Bean wiring (spec PR #564 §2-3 옵션 a — api 모듈 단독).
 *
 * Phase 1 LOCK:
 *   - api 모듈 단독 Bean 발행 (batch·consumer Bean 별도 wiring은 Phase 2 후속)
 *   - magicjar.kis.rate-limiter.enabled=false 시 Bean 0 → caller는 직접 sleep fallback (회귀 마진 LOCK)
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 메커니즘
 *   - ADR 0038 — Redis 인프라 재사용 (spring.data.redis.host/port 재사용)
 *   - ADR 0088 — persistence-shared 패턴 (Phase 2 모듈 분리 시 1:1 reuse 권장)
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 Spring Data Redis `LettuceConnectionFactory`와 별도로 Bucket4j 전용 `RedisClient` 발행
 *        → Bucket4j는 raw Lettuce `StatefulRedisConnection<String, byte[]>` codec 의존 (Spring Data 추상화 불충분)
 *   - 🔄 `LettuceBasedProxyManager` 직접 발행 (`spring-boot-starter-data-redis` 의존 그대로 두고 별도 클라이언트 1개 추가)
 *   - ✨ ExpirationAfterWriteStrategy `basedOnTimeForRefillingBucketUpToMax` — TTL 자동 갱신 (Redis key 누수 0)
 */
@Configuration
@EnableConfigurationProperties(KisRateLimitProperties::class)
@ConditionalOnProperty(
    prefix = "magicjar.kis.rate-limiter",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class KisRateLimiterConfiguration {
    /**
     * Bucket4j 전용 Lettuce RedisClient (Spring Data Redis와 별도 발행).
     *
     * 사유:
     *   - Bucket4j 8.x Lettuce backend는 raw `StatefulRedisConnection<String, byte[]>` codec 의존
     *   - Spring Data Redis `LettuceConnectionFactory`는 추상화 wrapper → Bucket4j ProxyManager 직접 호환 0
     *   - 별도 client 발행 cost = 신규 TCP connection 1 (Redis cost 0.1%)
     *
     * destroyMethod="shutdown" 으로 graceful close 보장 (ADR 0045 SIGTERM 정합).
     */
    @Bean(destroyMethod = "shutdown")
    fun kisRateLimitRedisClient(
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

    /**
     * Bucket4j 단일 connection (long-lived, share 안전).
     *
     * destroyMethod="close" → graceful close.
     */
    @Bean(destroyMethod = "close")
    fun kisRateLimitRedisConnection(kisRateLimitRedisClient: RedisClient): StatefulRedisConnection<String, ByteArray> {
        val codec: RedisCodec<String, ByteArray> = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
        return kisRateLimitRedisClient.connect(codec)
    }

    /**
     * Bucket4j ProxyManager — 모든 bucket 발행 진입점.
     *
     * ExpirationAfterWriteStrategy: refill 윈도(1s)의 10배 TTL 자동 갱신 → 미사용 시 자동 정리.
     */
    @Bean
    fun kisRateLimitProxyManager(
        kisRateLimitRedisConnection: StatefulRedisConnection<String, ByteArray>,
        props: KisRateLimitProperties,
    ): ProxyManager<String> =
        LettuceBasedProxyManager
            .builderFor(kisRateLimitRedisConnection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(props.bucketTtlSeconds),
                ),
            ).build()

    /**
     * KisRateLimiterPort 어댑터 Bean — domain port LOCK 정합.
     *
     * 호출자 (Phase 2 합류 후):
     *   - api: CandleRefreshService, KisChartCandleAdapter, KisInquireBalanceHttpAdapter
     *   - consumer: KisRestCandleAdapter, KisReconcileBalanceAdapter, KisOrderHttpAdapter (Bean 별도 wiring)
     *   - batch: backfill 어댑터 (Bean 별도 wiring)
     */
    @Bean
    fun kisRateLimiterPort(
        kisRateLimitProxyManager: ProxyManager<String>,
        props: KisRateLimitProperties,
        @Autowired(required = false) systemEventLogger: SystemEventLogger?,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ): KisRateLimiterPort = RedisKisRateLimiterAdapter(kisRateLimitProxyManager, props, systemEventLogger, meterRegistry)
}
