package kr.spdaewoon.magicjar.api.service.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 사용자 전략 ACTIVE/PAUSED 전환에 맞춰 STRATEGY owner 실시간 감시 수요를 발행한다.
 *
 * 저장된 strategy.codes 전체를 대상으로 하므로 한 전략에 여러 종목이 매핑되어도 동일 ownerId 아래에서 함께 감시된다.
 * Kafka 발행 실패는 상태 전환 자체를 막지 않는다. Consumer의 ActiveStrategyDemandRegistrar가 주기적으로 보강한다.
 */
@Service
class StrategyRealtimeDemandPublisher(
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(StrategyRealtimeDemandPublisher::class.java)

    fun enter(strategy: Strategy) {
        publish(action = "ENTER", strategy = strategy, includeInterval = true)
    }

    fun exit(strategy: Strategy) {
        publish(action = "EXIT", strategy = strategy, includeInterval = false)
    }

    private fun publish(
        action: String,
        strategy: Strategy,
        includeInterval: Boolean,
    ) {
        val strategyId = strategy.id ?: return
        // bare ownerId — owner enum prefix(`strategy:`)는 consumer 스냅샷 직렬화에서 1회 부여(이중 prefix 회피).
        // consumer ActiveStrategyDemandRegistrar도 동일 bare(`<id>`)로 보강하므로 OwnerKey 일치(release 누수 0).
        val ownerId = "$strategyId"
        val codes = normalizedCodes(strategy.codes)
        if (codes.isEmpty()) return
        val interval = strategy.intervals.firstOrNull()?.takeIf { it.isNotBlank() } ?: strategy.definition.tf

        codes.forEachIndexed { index, code ->
            runCatching {
                val payload =
                    mutableMapOf<String, Any>(
                        "action" to action,
                        "owner" to "STRATEGY",
                        "ownerId" to ownerId,
                        "code" to code,
                        "source" to "USER_STRATEGY",
                        "ts" to Instant.now().toString(),
                    )
                if (action == "ENTER") {
                    payload["priority"] = STRATEGY_REALTIME_PRIORITY_BASE + index
                    payload["ttlSeconds"] = STRATEGY_REALTIME_TTL_SECONDS
                }
                if (includeInterval) {
                    payload["interval"] = interval
                }
                kafkaTemplate.send(TOPIC, code, objectMapper.writeValueAsString(payload))
            }.onFailure { ex ->
                log.warn("전략 realtime demand 발행 실패(무시): action={} ownerId={} code={} err={}", action, ownerId, code, ex.message)
            }
        }
        log.info("전략 realtime demand 발행: action={} ownerId={} interval={} count={}", action, ownerId, interval, codes.size)
    }

    private fun normalizedCodes(codes: List<String>): List<String> =
        codes
            .map { it.trim() }
            .filter { CODE_REGEX.matches(it) }
            .distinct()

    private companion object {
        const val TOPIC = KafkaTopics.Market.REALTIME_DEMAND
        const val STRATEGY_REALTIME_PRIORITY_BASE = 40
        const val STRATEGY_REALTIME_TTL_SECONDS = 180L
        val CODE_REGEX = Regex("^\\d{6}$")
    }
}
