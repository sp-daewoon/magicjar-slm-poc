package kr.spdaewoon.magicjar.consumer.config

import kr.spdaewoon.magicjar.consumer.infrastructure.observability.TraceIdKafkaRecordInterceptor
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.SerializationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.boot.ssl.SslBundles
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.util.backoff.FixedBackOff

/**
 * Kafka DLQ + 재시도 정책 + DLT publishing (ADR 0084 P0-4 — spec §2-4 LOCK).
 *
 * ### 외부 코드 리뷰 #7 흡수 (2026-05-18)
 * 현 OrderRouterKafkaListener line 266~268 — unexpected 예외 시 ack 보류 → Kafka 재처리 무한 loop risk
 *   + KIS rate limit (ADR 0037) 초과 risk + offset 정체.
 *
 * ### 정책 LOCK
 * - **재시도 횟수**: 3회 (1초 간격, FixedBackOff)
 * - **재시도 가능 예외**: 일반 RuntimeException (네트워크 timeout · transient DB 결함 등)
 * - **재시도 불가 예외** (즉시 DLT, 재시도 0회 skip):
 *   - DeserializationException (Spring Kafka 표준 — Jackson parse fail)
 *   - SerializationException (Apache Kafka 표준)
 * - **DLT publish**: DeadLetterPublishingRecoverer로 `<topic>.DLT` 자동 발사
 * - **DLT 모니터링**: OrderDltMonitorListener — P0 알림 (자본 path)
 *
 * ### 적용 범위
 * 본 Bean은 Spring Boot 자동 설정의 `kafkaListenerContainerFactory` (default)를 override (@Primary).
 * 대부분 @KafkaListener는 RECORD ack 기본 정책을 사용한다.
 * 자본 path에서 직접 offset을 제어해야 하는 listener는 `manualImmediateKafkaListenerContainerFactory`를 명시한다.
 *
 * 예외: WatchlistChangedKafkaConfig는 본인 전용 factory 사용 — 별도 정책 미적용 (slot router 영역 분리).
 *
 * ### partition·key 보존
 * DeadLetterPublishingRecoverer는 원본 record key/value/headers를 그대로 DLT로 forward.
 * → DLT 모니터링이 원본 partition key(userId) 추적 가능.
 *
 * ### ConsumerFactory inject 정책 (hotfix 2026-05-18 — v3.6.16 차단 해소)
 * **이슈**: WatchlistChangedKafkaConfig가 `ConsumerFactory<String, String>` (named bean) 발행 시
 * Spring Boot `KafkaAutoConfiguration.kafkaConsumerFactory` 의 `@ConditionalOnMissingBean(ConsumerFactory.class)`
 * 조건이 false → default `ConsumerFactory<*, *>` 미등록 → `kafkaListenerContainerFactory` autowire fail.
 *
 * **fix**: Spring Boot `KafkaAnnotationDrivenConfiguration.kafkaListenerContainerFactory` 본인이 사용하는
 * `ObjectProvider<ConsumerFactory<Object, Object>>` pattern 모방.
 *   1. ObjectProvider로 graceful inject (absent OK)
 *   2. 부재 시 `KafkaProperties.buildConsumerProperties()`로 fallback default 생성 — Spring Boot autoconfig 동일 path
 *   3. 결과: WatchlistChangedKafkaConfig 등 named ConsumerFactory가 등록되어도 default 보장
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-8).
 */
@Configuration
class KafkaErrorHandlerConfig {
    private val log = LoggerFactory.getLogger(KafkaErrorHandlerConfig::class.java)

    companion object {
        /** 재시도 간격 (ms) — spec §A FixedBackOff(1000L, 3L) 정합. */
        const val RETRY_INTERVAL_MS = 1000L

        /** 최대 재시도 횟수 — spec §A. 총 4회 시도 (최초 1회 + 재시도 3회) 후 DLT. */
        const val MAX_RETRY_ATTEMPTS = 3L
    }

    /**
     * DLT publish 발신자 — DeadLetterPublishingRecoverer.
     *
     * 기본 destination resolver: `<원본 topic>.DLT` (suffix `.DLT`).
     * partition resolver는 default(원본 partition 그대로) — partition 0 일 때 자동 처리.
     */
    @Bean
    fun deadLetterPublishingRecoverer(kafkaTemplate: KafkaTemplate<String, String>): DeadLetterPublishingRecoverer =
        DeadLetterPublishingRecoverer(kafkaTemplate) { record, ex ->
            val targetTopic = "${record.topic()}.DLT"
            log.error(
                "DLT publish 발사: src_topic={} target={} partition={} offset={} key={} err={}",
                record.topic(),
                targetTopic,
                record.partition(),
                record.offset(),
                record.key(),
                ex.message,
            )
            // partition 0 forward (원본 partition 무관 — DLT 1 partition 정합)
            TopicPartition(targetTopic, 0)
        }

    /**
     * DefaultErrorHandler — 재시도 정책 + DLT publishing 통합.
     *
     * - retryable (FixedBackOff): 1s × 3회 재시도 후 DLT
     * - non-retryable (즉시 DLT): DeserializationException · SerializationException
     */
    @Bean
    fun kafkaErrorHandler(recoverer: DeadLetterPublishingRecoverer): DefaultErrorHandler {
        val backOff = FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS)
        val handler = DefaultErrorHandler(recoverer, backOff)
        // 재시도 불가 예외 등록 — 즉시 DLT (재시도 0회 skip)
        handler.addNotRetryableExceptions(
            DeserializationException::class.java,
            SerializationException::class.java,
            IllegalArgumentException::class.java,
        )
        // 재시도 log 노이즈 줄이기 위해 retry log 활성화
        handler.setRetryListeners(
            { record, ex, deliveryAttempt ->
                log.warn(
                    "Kafka retry attempt={}: topic={} partition={} offset={} key={} err={}",
                    deliveryAttempt,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    ex.message,
                )
            },
        )
        return handler
    }

    /**
     * Spring Boot 자동 설정의 `kafkaListenerContainerFactory` override.
     *
     * @Primary로 기본 factory 대체 — 명시적 containerFactory가 없는 @KafkaListener는 RECORD ack를 사용.
     * concurrency 등 application.yml `spring.kafka.listener.*` 설정은 `ConcurrentKafkaListenerContainerFactoryConfigurer`가
     * 자동 주입하고, ackMode만 자본 path 재처리 루프 방지를 위해 RECORD로 고정한다.
     *
     * 예외: WatchlistChangedKafkaConfig는 별도 named factory (`watchlistChangedKafkaListenerContainerFactory`) 보유 →
     * @KafkaListener(containerFactory = "watchlistChangedKafkaListenerContainerFactory")로 명시 시 본 default 우회.
     *
     * ### ConsumerFactory inject — ObjectProvider pattern (hotfix 2026-05-18)
     * Spring Boot `KafkaAnnotationDrivenConfiguration` 본인이 사용하는 pattern 모방.
     * WatchlistChangedKafkaConfig 등이 named `ConsumerFactory<String, String>` bean 발행 시
     * Spring Boot `KafkaAutoConfiguration.kafkaConsumerFactory@ConditionalOnMissingBean(ConsumerFactory.class)`
     * 조건이 false → default ConsumerFactory 미등록 → 본 메서드 hard inject fail.
     * 해결: ObjectProvider로 graceful inject + 부재 시 `KafkaProperties.buildConsumerProperties()` fallback.
     */
    @Bean
    @Primary
    fun kafkaListenerContainerFactory(
        configurer: ConcurrentKafkaListenerContainerFactoryConfigurer,
        consumerFactoryProvider: ObjectProvider<ConsumerFactory<Any, Any>>,
        sslBundlesProvider: ObjectProvider<SslBundles>,
        kafkaProperties: KafkaProperties,
        errorHandler: DefaultErrorHandler,
        traceIdRecordInterceptor: TraceIdKafkaRecordInterceptor,
    ): ConcurrentKafkaListenerContainerFactory<Any, Any> {
        val factory = ConcurrentKafkaListenerContainerFactory<Any, Any>()
        // Spring Boot KafkaAnnotationDrivenConfiguration.kafkaListenerContainerFactory pattern 정합
        val consumerFactory: ConsumerFactory<Any, Any> =
            consumerFactoryProvider.getIfAvailable {
                DefaultKafkaConsumerFactory(
                    kafkaProperties.buildConsumerProperties(sslBundlesProvider.ifAvailable),
                )
            }
        configurer.configure(factory, consumerFactory)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        factory.setCommonErrorHandler(errorHandler)
        // ADR 0084 P1-3 — MDC traceId 분산 추적: 모든 @KafkaListener 진입 시 header → MDC 복원
        factory.setRecordInterceptor(traceIdRecordInterceptor)
        return factory
    }

    /**
     * 직접 ack 제어가 필요한 자본 path listener 전용 factory.
     *
     * KIS 주문 submit 이후 예외 처리처럼 "재처리하면 중복 실주문"이 될 수 있는 구간은 listener가 명시적으로
     * acknowledge 시점을 결정한다.
     */
    @Bean("manualImmediateKafkaListenerContainerFactory")
    fun manualImmediateKafkaListenerContainerFactory(
        configurer: ConcurrentKafkaListenerContainerFactoryConfigurer,
        consumerFactoryProvider: ObjectProvider<ConsumerFactory<Any, Any>>,
        sslBundlesProvider: ObjectProvider<SslBundles>,
        kafkaProperties: KafkaProperties,
        errorHandler: DefaultErrorHandler,
        traceIdRecordInterceptor: TraceIdKafkaRecordInterceptor,
    ): ConcurrentKafkaListenerContainerFactory<Any, Any> {
        val factory = ConcurrentKafkaListenerContainerFactory<Any, Any>()
        val consumerFactory: ConsumerFactory<Any, Any> =
            consumerFactoryProvider.getIfAvailable {
                DefaultKafkaConsumerFactory(
                    kafkaProperties.buildConsumerProperties(sslBundlesProvider.ifAvailable),
                )
            }
        configurer.configure(factory, consumerFactory)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        factory.setCommonErrorHandler(errorHandler)
        factory.setRecordInterceptor(traceIdRecordInterceptor)
        return factory
    }
}
