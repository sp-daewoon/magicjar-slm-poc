package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.risk.RiskAlertMessage
import kr.spdaewoon.magicjar.domain.risk.RiskGatewayResult
import kr.spdaewoon.magicjar.domain.risk.RiskVerdict
import kr.spdaewoon.magicjar.domain.signal.Signal
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Risk 위반(risk.violation.v1) Kafka publish 어댑터 (ADR 0067 §7-1 RiskViolationKafkaProducer + F-1 정합).
 *
 * ### publish 토픽
 *   `risk.violation.v1` — ADR 0044 Notification System 진입점 + Phase 2 STOMP fanout 연계
 *
 * partition key = `userId` — 사용자별 ordering 보장.
 *
 * ### payload schema (ADR 0067 §11-4 ADR 0044 row + Phase 1 자체 정의)
 * { type, signalId, strategyId, userId, code, rule, verdict, reason, rawReason,
 *   contextJson, blockedAt(epochSeconds), ts, alertTitle, alertMessage, alertFormula, alertSetting, alertAction }
 *
 * ### 호출 시점 (ADR 0067 §7-2 단계 4 BLOCKED 분기)
 *  SignalGeneratedKafkaListener BLOCKED:
 *    RiskEventRepository.save → SignalRiskStatusUpdater.markBlocked → RiskNotifier.notifyViolation
 *      → **RiskViolationKafkaProducer.publish** ← 본 컴포넌트
 *      → AutoPauseGuard.checkAndPauseIfNeeded
 *
 * ### 헌법 §3 KIS-first 영향 0
 * Kafka publish only — KIS REST/WS 직접 호출 0.
 *
 * spec 인용: ADR 0067 §7-1 RiskViolationKafkaProducer.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class RiskViolationKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RiskViolationKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Risk.VIOLATION
    }

    /**
     * BLOCKED 시그널 → risk.violation.v1 publish.
     */
    fun publish(
        signal: Signal,
        blocked: RiskGatewayResult.Blocked,
        riskEventId: Long? = null,
    ) {
        try {
            val now = Instant.now()
            val alert = RiskAlertMessage.from(blocked.rule, blocked.reason, blocked.context, signal.code)
            val payload =
                objectMapper.writeValueAsString(
                    mutableMapOf<String, Any?>(
                        "type" to "BLOCKED",
                        "riskEventId" to riskEventId,
                        "signalId" to signal.id,
                        "strategyId" to signal.strategyId,
                        "userId" to signal.userId,
                        "code" to signal.code,
                        "rule" to blocked.rule.name,
                        "ruleType" to blocked.rule.name,
                        "verdict" to RiskVerdict.BLOCKED.name,
                        "reason" to alert.message,
                        "rawReason" to blocked.reason,
                        "contextJson" to blocked.context.mapValues { it.value?.toString() },
                        "blockedAt" to now.epochSecond,
                        "violatedAt" to now.epochSecond,
                        "ts" to now.toString(),
                    ).apply {
                        putAll(alert.toPayloadFields())
                    },
                )
            kafkaTemplate.send(TOPIC, signal.userId, payload)
            log.debug(
                "RiskViolationKafkaProducer publish: topic={} userId={} signalId={} rule={}",
                TOPIC,
                signal.userId,
                signal.id,
                blocked.rule,
            )
        } catch (e: Exception) {
            log.error(
                "RiskViolationKafkaProducer publish 실패: userId={} signalId={} err={}",
                signal.userId,
                signal.id,
                e.message,
            )
        }
    }
}
