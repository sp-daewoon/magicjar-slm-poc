package kr.spdaewoon.magicjar.persistence.shared.event

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * SystemEvent → Kafka `system.event.v1` publish (P2 STOMP fanout 트리거 source).
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6.2
 *
 * ### partition key 정책
 * - userId 있으면 → userId (사용자별 ordering 보장 — UI 알림센터 시계열 정합)
 * - userId null  → "broadcast" (system-wide 이벤트 — STOMP `/topic/system.event.broadcast`)
 *
 * ### 실패 정책
 * - send future 에 callback 만 부착하고 caller thread 는 block 하지 않음
 * - Kafka broker down 시 callback log.warn, DB source-of-truth 는 유지 (graceful)
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Component
class SystemEventKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SystemEventKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.System.EVENT
        const val BROADCAST_KEY = "broadcast"
    }

    fun publish(event: SystemEvent) {
        val partitionKey = event.userId ?: BROADCAST_KEY
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate
            .send(TOPIC, partitionKey, payload)
            .whenComplete { _, error ->
                if (error != null) {
                    log.warn(
                        "SystemEvent publish 실패 — DB 영속만 유지: category={} action={} partitionKey={} err={}",
                        event.category,
                        event.action,
                        partitionKey,
                        error.message,
                    )
                } else {
                    log.debug(
                        "SystemEvent publish OK: category={} severity={} action={} partitionKey={}",
                        event.category,
                        event.severity,
                        event.action,
                        partitionKey,
                    )
                }
            }
    }
}
