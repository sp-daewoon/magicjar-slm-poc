package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeDemandOwner
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeSubscriptionRouter
import kr.spdaewoon.magicjar.consumer.reconciliation.ManagedCandleUniverseCache
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * kafka.api.cmd.market.realtime-demand.v1 consumer.
 *
 * Strategy/Screener가 찾아낸 후보는 watchlist에 자동 삽입하지 않는다.
 * 이 topic은 "시스템이 잠시 실시간으로 관찰해야 하는 종목" 수요만 전달하고,
 * RealtimeSubscriptionRouter가 owner/priority/TTL 단위로 slot을 배정한다.
 */
@Component
class RealtimeDemandKafkaListener(
    private val objectMapper: ObjectMapper,
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
    private val universeCache: ManagedCandleUniverseCache,
) {
    private val log = LoggerFactory.getLogger(RealtimeDemandKafkaListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Market.REALTIME_DEMAND],
        groupId = "magicjar-consumer-realtime-demand",
        containerFactory = "manualImmediateKafkaListenerContainerFactory",
    )
    fun onMessage(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val action = payload["action"]?.toString()?.uppercase() ?: "ENTER"
            val owner =
                RealtimeDemandOwner.from(payload["owner"]?.toString()) ?: run {
                    log.warn("market.realtime.demand owner 누락/오류 — payload={}", record.value().take(200))
                    ack.acknowledge()
                    return
                }
            val ownerId = payload["ownerId"]?.toString() ?: owner.name
            val priority = payload["priority"]?.toString()?.toIntOrNull() ?: owner.defaultPriority
            val ttlSeconds = payload["ttlSeconds"]?.toString()?.toLongOrNull()
            val codes = resolveCodes(payload)

            if (codes.isEmpty()) {
                ack.acknowledge()
                return
            }

            when (action) {
                "ENTER" ->
                    codes.forEach { code ->
                        realtimeSubscriptionRouter.request(owner, ownerId, code, priority, ttlSeconds)
                        if (owner == RealtimeDemandOwner.STRATEGY) {
                            universeCache.recordStrategyCandidate(code, ttlSeconds)
                        }
                    }
                "EXIT" ->
                    codes.forEach { code ->
                        realtimeSubscriptionRouter.release(owner, ownerId, code)
                    }
                else -> log.warn("market.realtime.demand 알 수 없는 action: {} — 무시", action)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "{} 처리 실패 — offset={} err={}",
                KafkaTopics.Market.REALTIME_DEMAND,
                record.offset(),
                e.message,
                e,
            )
            ack.acknowledge()
        }
    }

    private fun resolveCodes(payload: Map<String, Any?>): List<String> {
        val code = payload["code"]?.toString()?.takeIf { it.matches(Regex("^\\d{6}$")) }
        if (code != null) return listOf(code)

        @Suppress("UNCHECKED_CAST")
        val codes = payload["codes"] as? List<Any?> ?: return emptyList()
        return codes
            .mapNotNull { it?.toString() }
            .filter { it.matches(Regex("^\\d{6}$")) }
            .distinct()
    }
}
