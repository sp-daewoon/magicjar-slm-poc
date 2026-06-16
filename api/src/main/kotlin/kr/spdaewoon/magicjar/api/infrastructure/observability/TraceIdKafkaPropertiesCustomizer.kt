package kr.spdaewoon.magicjar.api.infrastructure.observability

import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory

/**
 * MDC traceId producer interceptor 자동 등록 configuration
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * ### 동작
 * Spring Boot Kafka auto-config가 생성한 `DefaultKafkaProducerFactory`에
 * `interceptor.classes` 프로퍼티를 후처리(post-process)로 주입 →
 * 모든 `KafkaTemplate.send()` 호출이 `TraceIdKafkaProducerInterceptor`를 거치게 됨.
 *
 * ### 다른 interceptor 보존
 * 기존 `interceptor.classes` 프로퍼티 값이 있으면 콤마 분리 후 append —
 * 다른 운영 interceptor (예: Micrometer)와 공존 가능.
 *
 * ### scope
 * - api 모듈: KafkaTemplate 사용처 일괄 (OrderIntent·ManualOrderIntent·Disclosure·News·SignalGenerated 등).
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
@Configuration
class TraceIdKafkaPropertiesCustomizer {
    /**
     * `DefaultKafkaProducerFactory` post-processor — `interceptor.classes` 프로퍼티 추가.
     *
     * Spring Boot가 producer factory bean 생성 후 본 customizer가 후처리로 호출 →
     * `updateConfigs` 로 interceptor 등록.
     */
    @Bean
    fun traceIdProducerFactoryCustomizer(): DefaultKafkaProducerFactoryCustomizer =
        DefaultKafkaProducerFactoryCustomizer { factory: DefaultKafkaProducerFactory<*, *> ->
            val key = ProducerConfig.INTERCEPTOR_CLASSES_CONFIG
            val interceptorClass = TraceIdKafkaProducerInterceptor::class.java.name
            val existing = factory.configurationProperties[key]?.toString()
            val merged =
                when {
                    existing.isNullOrBlank() -> interceptorClass
                    existing.split(",").map { it.trim() }.contains(interceptorClass) -> existing
                    else -> "$existing,$interceptorClass"
                }
            factory.updateConfigs(mapOf(key to merged))
        }
}
