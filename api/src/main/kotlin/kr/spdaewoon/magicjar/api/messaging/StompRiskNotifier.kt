package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * `kafka.consumer.evt.risk.violation.v1` Kafka 토픽 → STOMP `/topic/stomp.api.risk.user.{userId}` fanout.
 *
 * RiskViolationKafkaProducer(consumer 모듈)가 발행한 risk 위반 이벤트를 Kafka에서 수신하여
 * 해당 사용자의 STOMP 구독 토픽으로 실시간 전달 (ADR 0067 §5-3 F-1).
 *
 * ### groupId
 * `magicjar-api-stomp-risk` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 `magicjar-risk-gateway` 그룹과 분리.
 *
 * ### STOMP 토픽
 * `/topic/stomp.api.risk.user.{userId}` — 사용자별 fanout (userId는 payload userId 필드).
 *
 * ### 이벤트 type 2건
 * - `BLOCKED` — 즉시 위반 차단
 * - `AUTO_PAUSED` — strategy 연속 BLOCKED N회 → 신규 BUY 차단 전환
 *
 * ### 에러 처리
 * 역직렬화 실패 시 log.error + skip (메시지 손실 허용 — risk_event DB 재조회 가능).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class StompRiskNotifier(
    private val brokerMessagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
    private val stompEventPersister: StompEventPersister,
) {
    private val log = LoggerFactory.getLogger(StompRiskNotifier::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Risk.VIOLATION],
        groupId = "magicjar-api-stomp-risk",
    )
    fun onRiskViolation(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val rawMap = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val userId =
                rawMap["userId"]?.toString() ?: run {
                    log.warn("{} userId null — offset={}", KafkaTopics.Risk.VIOLATION, record.offset())
                    return
                }

            val payload = toRiskEventDto(rawMap)
            val topic = StompDestinations.riskUser(userId)
            // ADR 0084 P1-2: publish 직전 stomp_event 영속 (best-effort)
            stompEventPersister.persist(
                userId = userId,
                channel = "risk",
                eventType = payload.type, // BLOCKED · AUTO_PAUSED
                payload = payload,
            )
            brokerMessagingTemplate.convertAndSend(topic, payload)
            log.debug(
                "STOMP {} 발행: rule={} verdict={} type={}",
                topic,
                rawMap["rule"],
                rawMap["verdict"],
                rawMap["type"],
            )
        } catch (e: Exception) {
            log.error(
                "{} STOMP 포워드 실패: offset={} err={}",
                KafkaTopics.Risk.VIOLATION,
                record.offset(),
                e.message,
            )
        }
    }

    // --- private helper ---

    private fun toRiskEventDto(raw: Map<String, Any?>): RiskEventDto =
        RiskEventDto(
            type = raw["type"]?.toString() ?: "BLOCKED",
            userId = raw["userId"]?.toString() ?: "",
            signalId = toLong(raw["signalId"]),
            strategyId = toLong(raw["strategyId"]),
            rule = raw["rule"]?.toString() ?: "",
            verdict = raw["verdict"]?.toString() ?: "BLOCKED",
            reason = raw["alertMessage"]?.toString() ?: raw["reason"]?.toString() ?: "",
            rawReason = raw["rawReason"]?.toString(),
            alertTitle = raw["alertTitle"]?.toString(),
            alertMessage = raw["alertMessage"]?.toString(),
            alertFormula = raw["alertFormula"]?.toString(),
            alertSetting = raw["alertSetting"]?.toString(),
            alertAction = raw["alertAction"]?.toString(),
            ts = raw["ts"]?.toString() ?: "",
        )

    private fun toLong(value: Any?): Long? =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
}

/**
 * Risk 위반 이벤트 STOMP 전달 DTO.
 *
 * type: BLOCKED(즉시 위반) · AUTO_PAUSED(strategy 연속 N회 신규 BUY 차단 전환).
 */
data class RiskEventDto(
    val type: String,
    val userId: String,
    val signalId: Long?,
    val strategyId: Long?,
    val rule: String,
    val verdict: String,
    val reason: String,
    val rawReason: String? = null,
    val alertTitle: String? = null,
    val alertMessage: String? = null,
    val alertFormula: String? = null,
    val alertSetting: String? = null,
    val alertAction: String? = null,
    val ts: String,
)
