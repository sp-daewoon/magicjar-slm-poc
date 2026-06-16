package kr.spdaewoon.magicjar.api.messaging

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * KillSwitch 토글 Kafka producer (ADR 0074 §2-3-3 + ADR 0070 §2-11 schema 확장 정합).
 *
 * 토픽: killswitch.engaged.v1 (기존 — schema 확장, 별도 토픽 신설 X)
 * schema 확장: engaged:boolean 추가 (ADR 0074 §3-4 채택 결정 §c)
 *
 * 이벤트 스키마 v1 (확장):
 * {
 *   "eventId": "<uuid>",
 *   "userId": "<userId>",
 *   "engaged": true/false,         ← 신규 — 토글 후 상태
 *   "reason": "<reason>",
 *   "updatedBy": "AUTO" | "USER:<userId>",
 *   "occurredAt": "<instant>",
 *   "schemaVersion": 1
 * }
 *
 * 기존 listener(SlackP0 dispatcher 등)는 engaged=true filter만 추가하여 false 이벤트 무시.
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Component
class KillSwitchEngagedProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(KillSwitchEngagedProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Risk.KILLSWITCH_ENGAGED
    }

    /**
     * KillSwitch 토글 이벤트 발행.
     *
     * @param userId 대상 사용자 ID
     * @param engaged true=ON, false=OFF
     * @param reason 토글 사유
     * @param updatedBy "USER:<userId>" 또는 "AUTO"
     */
    fun publish(
        userId: String,
        engaged: Boolean,
        reason: String,
        updatedBy: String,
    ) {
        val eventId = UUID.randomUUID().toString()
        val occurredAt = Instant.now()
        // reason JSON escape — 안전을 위해 큰따옴표 및 백슬래시 escape
        val escapedReason = reason.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedUpdatedBy = updatedBy.replace("\\", "\\\\").replace("\"", "\\\"")
        val payload =
            """{"eventId":"$eventId","userId":"$userId","engaged":$engaged,"reason":"$escapedReason","updatedBy":"$escapedUpdatedBy","occurredAt":"$occurredAt","schemaVersion":1}"""

        kafkaTemplate
            .send(TOPIC, userId, payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "KillSwitch 이벤트 publish 실패 — userId={}**** engaged={} err={}",
                        userId.take(4),
                        engaged,
                        ex.message,
                    )
                } else {
                    log.info(
                        "KillSwitch 이벤트 publish OK — eventId={} engaged={} partition={}",
                        eventId,
                        engaged,
                        result?.recordMetadata?.partition(),
                    )
                }
            }
    }
}
