package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Kafka `reconcile.run.v1` · `reconcile.drift.v1` · `killswitch.engaged.v1` 3 topic
 * → STOMP `/topic/recon.user.{userId}` fanout.
 *
 * (ADR 0041·0070 §2-7 + spec §2-3 + spec §7-1 단일 토픽 + event discriminator 4 LOCK)
 *
 * ### groupId
 * `magicjar-api-stomp-recon` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 `magicjar-consumer-recon-*` 그룹과 분리.
 *
 * ### STOMP 토픽
 * `/topic/recon.user.{userId}` — 사용자별 fanout (userId는 payload userId 필드).
 *
 * ### 이벤트 type 4건 (spec §7-1 LOCK)
 * - `reconcile.run.started`    — reconcile.run.v1 수신 (status=RUNNING)
 * - `reconcile.run.completed`  — reconcile.run.v1 수신 (status ∈ RECONCILED·DRIFT·DRIFT_PRICE·ERROR)
 * - `reconcile.drift.detected` — reconcile.drift.v1 수신 (종목별 drift)
 * - `killswitch.auto.engaged`  — killswitch.engaged.v1 수신 (triggeredBy=AUTO)
 *
 * ### 에러 처리
 * 역직렬화 실패 시 log.error + skip (손실 허용 — DB reconcile_run 재조회 가능).
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class StompReconcileNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
    private val stompEventPersister: StompEventPersister,
) {
    private val log = LoggerFactory.getLogger(StompReconcileNotifier::class.java)

    @KafkaListener(
        topics = [
            KafkaTopics.Reconcile.RUN,
            KafkaTopics.Reconcile.DRIFT,
            KafkaTopics.Risk.KILLSWITCH_ENGAGED,
        ],
        groupId = "magicjar-api-stomp-recon",
    )
    fun onReconcileEvent(
        record: ConsumerRecord<String, String>,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val rawMap = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val userId =
                rawMap["userId"]?.toString() ?: run {
                    log.warn("reconcile event userId null — topic={} offset={}", topic, record.offset())
                    return
                }

            val payload = toStompPayload(rawMap, topic)
            val destination = StompDestinations.reconUser(userId)
            // ADR 0084 P1-2: publish 직전 stomp_event 영속 (best-effort)
            stompEventPersister.persist(
                userId = userId,
                channel = "recon",
                eventType = payload.eventType,
                payload = payload,
            )
            messagingTemplate.convertAndSend(destination, payload)

            log.debug(
                "STOMP {} 발행: eventType={} runId={} userId={}****",
                destination,
                payload.eventType,
                payload.runId,
                userId.take(4),
            )
        } catch (e: Exception) {
            log.error(
                "reconcile event STOMP 포워드 실패: topic={} offset={} err={}",
                topic,
                record.offset(),
                e.message,
            )
        }
    }

    // --- private helper ---

    private fun toStompPayload(
        raw: Map<String, Any?>,
        topic: String,
    ): ReconcileStompPayload {
        val status = raw["status"]?.toString()
        val eventType =
            when (topic) {
                KafkaTopics.Reconcile.RUN ->
                    if (status == "RUNNING") {
                        "reconcile.run.started"
                    } else {
                        "reconcile.run.completed"
                    }
                KafkaTopics.Reconcile.DRIFT -> "reconcile.drift.detected"
                KafkaTopics.Risk.KILLSWITCH_ENGAGED -> "killswitch.auto.engaged"
                else -> {
                    log.warn("알 수 없는 reconcile 이벤트 토픽: {}", topic)
                    "unknown"
                }
            }

        return ReconcileStompPayload(
            eventType = eventType,
            runId = toLong(raw["runId"]),
            userId = raw["userId"]?.toString() ?: "",
            status = status,
            driftCount = toInt(raw["driftCount"]),
            symbol = raw["symbol"]?.toString(),
            kisQty = toInt(raw["kisQty"]),
            internalQty = toInt(raw["internalQty"]),
            qtyDiff = toInt(raw["qtyDiff"]),
            reason = raw["reason"]?.toString(),
            triggeredBy = raw["triggeredBy"]?.toString(),
            startedAt = raw["startedAt"]?.toString(),
            finishedAt = raw["finishedAt"]?.toString(),
            engagedAt = raw["engagedAt"]?.toString(),
            triggerSource = raw["triggerSource"]?.toString(),
        )
    }

    private fun toLong(value: Any?): Long? =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    private fun toInt(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
}

/**
 * Reconcile 이벤트 STOMP 전달 DTO (ADR 0070 §2-7 + spec §7-1 4 event discriminator).
 *
 * eventType 4건:
 * - reconcile.run.started    — reconcile 시작 (파랑)
 * - reconcile.run.completed  — reconcile 완료 (status별 색상)
 * - reconcile.drift.detected — drift 검출 (빨강) — KillSwitch 자동 ON 트리거
 * - killswitch.auto.engaged  — KillSwitch 자동 ON (빨강 경고)
 */
@Schema(description = "Reconcile 이벤트 STOMP 페이로드 (/topic/recon.user.{userId})")
data class ReconcileStompPayload(
    @field:Schema(
        description = "이벤트 유형",
        example = "reconcile.run.completed",
        allowableValues = [
            "reconcile.run.started",
            "reconcile.run.completed",
            "reconcile.drift.detected",
            "killswitch.auto.engaged",
        ],
    )
    val eventType: String,
    @field:Schema(description = "reconcile run ID", example = "1001")
    val runId: Long?,
    @field:Schema(description = "사용자 ID", example = "12345678")
    val userId: String,
    @field:Schema(
        description = "실행 상태 (reconcile.run.* 이벤트에만)",
        example = "DRIFT",
        allowableValues = ["RUNNING", "RECONCILED", "DRIFT", "DRIFT_PRICE", "ERROR"],
    )
    val status: String?,
    @field:Schema(description = "drift 종목 수 (reconcile.run.completed 이벤트에만)", example = "2")
    val driftCount: Int?,
    @field:Schema(description = "drift 종목 코드 (reconcile.drift.detected 이벤트에만)", example = "005930")
    val symbol: String?,
    @field:Schema(description = "KIS 보유 수량 (reconcile.drift.detected 이벤트에만)", example = "100")
    val kisQty: Int?,
    @field:Schema(description = "내부 DB 보유 수량 (reconcile.drift.detected 이벤트에만)", example = "99")
    val internalQty: Int?,
    @field:Schema(description = "수량 차이 (reconcile.drift.detected 이벤트에만)", example = "1")
    val qtyDiff: Int?,
    @field:Schema(description = "KillSwitch 발동 사유 (killswitch.auto.engaged 이벤트에만)", example = "POSITION_DRIFT")
    val reason: String?,
    @field:Schema(description = "KillSwitch 발동 주체 (killswitch.auto.engaged 이벤트에만)", example = "AUTO")
    val triggeredBy: String?,
    @field:Schema(description = "reconcile 시작 시각 epoch (reconcile.run.started 이벤트에만)", example = "1715643600")
    val startedAt: String?,
    @field:Schema(description = "reconcile 종료 시각 epoch (reconcile.run.completed 이벤트에만)", example = "1715643602")
    val finishedAt: String?,
    @field:Schema(description = "KillSwitch 발동 시각 epoch (killswitch.auto.engaged 이벤트에만)", example = "1715643605")
    val engagedAt: String?,
    @field:Schema(description = "trigger 출처 (CRON·MANUAL·API — reconcile.run.* 이벤트에만)", example = "CRON")
    val triggerSource: String?,
)
