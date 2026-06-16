package kr.spdaewoon.magicjar.consumer.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

/**
 * watchlist.changed.v1 Kafka consumer 설정 (spec §4-1 정합).
 * - concurrency=1 (slot router race condition 회피)
 * - ackMode=MANUAL_IMMEDIATE (ADR 0045 graceful shutdown 정합)
 * - isolation.level=read_committed
 * - enable.auto.commit=false
 */
@Configuration
class WatchlistChangedKafkaConfig {
    @Value("\${spring.kafka.bootstrap-servers:localhost:9092}")
    private lateinit var bootstrapServers: String

    @Bean("watchlistChangedConsumerFactory")
    fun watchlistChangedConsumerFactory(): ConsumerFactory<String, String> {
        val props =
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "magicjar-consumer-watchlist-ingestion",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
            )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean("watchlistChangedKafkaListenerContainerFactory")
    fun watchlistChangedKafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = watchlistChangedConsumerFactory()
        factory.setConcurrency(1)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
        return factory
    }
}
