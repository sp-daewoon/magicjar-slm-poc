package kr.spdaewoon.magicjar.api.messaging

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * KIS 키 변경 Kafka producer (ADR 0074 §2-2-3 정합).
 *
 * 토픽: kafka.api.evt.system.kis-key.changed.v1 (retention 7d)
 * consumer: KisWebSocketBridge @KafkaListener — graceful reconnect 1회 (Phase 3)
 *
 * 이벤트 스키마 v1:
 * {
 *   "eventId": "<uuid>",
 *   "userId": "<userId>",
 *   "accountType": "REAL",
 *   "occurredAt": "<instant>",
 *   "schemaVersion": 1
 * }
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Component
class KisKeyChangedProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(KisKeyChangedProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.System.KIS_KEY_CHANGED
    }

    /**
     * KIS 키 변경 이벤트 발행.
     * accountType=REAL 시 consumer WS graceful reconnect trigger.
     */
    fun publish(
        userId: String,
        accountType: String,
    ) {
        val eventId = UUID.randomUUID().toString()
        val occurredAt = Instant.now()
        val payload =
            """{"eventId":"$eventId","userId":"$userId","accountType":"$accountType","occurredAt":"$occurredAt","schemaVersion":1}"""

        kafkaTemplate
            .send(TOPIC, userId, payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "KIS 키 변경 이벤트 publish 실패 — userId={}**** accountType={} err={}",
                        userId.take(4),
                        accountType,
                        ex.message,
                    )
                } else {
                    log.info(
                        "KIS 키 변경 이벤트 publish OK — eventId={} accountType={} partition={}",
                        eventId,
                        accountType,
                        result?.recordMetadata?.partition(),
                    )
                }
            }
    }
}
