package kr.spdaewoon.magicjar.consumer.infrastructure.observability

import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory

/**
 * MDC traceId producer interceptor 자동 등록 configuration (consumer 모듈)
 * (ADR 0084 P1-3 — spec §2-7 LOCK).
 *
 * api 모듈의 동일 클래스와 동일 동작 — consumer 모듈에서는 OrderRequestedKafkaProducer ·
 * OrderRejectedKafkaProducer 등 downstream Kafka publish 시 자동 부착.
 *
 * AI 호출 0 — ADR 0084 §2-8.
 */
@Configuration
class TraceIdKafkaPropertiesCustomizer {
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
