package kr.spdaewoon.magicjar.consumer.signal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.signal.Signal
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 시그널 Kafka publish 어댑터 (ADR 0066 §7-1 SignalKafkaProducer + §11-1 Phase 1 (6)).
 *
 * publish 토픽:
 *   `signal.generated.v1` (ADR 0066 §9-2 운영 영향 정합 — 1,000 msg/day)
 *
 * partition key = `userId` — 사용자별 ordering 보장 (Phase 2 STOMP fanout에서 활용).
 *
 * Phase 2 backend-core가 `signal.generated.v1` consumer 구현 →
 *   STOMP `/topic/signal.user.{userId}` fanout (ADR 0066 §2-4 D-1 채택).
 *
 * ### payload schema (ADR 0066 §5-2 정합)
 * { id, strategyId, userId, code, interval, ts, side, strength, reason, priceAtSignal,
 *   contextJson, createdAt }
 *
 * spec 인용: ADR 0066 §7-1 + §11-1 Phase 1 (6) + §9-2.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class SignalKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SignalKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Risk.SIGNAL_GENERATED
    }

    /**
     * 시그널 Kafka publish.
     *
     * partition key = userId — 사용자별 STOMP fanout ordering 보장.
     *
     * @param signal 영속 완료된 시그널 (id non-null 권장)
     */
    fun publish(signal: Signal) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "id" to signal.id,
                        "strategyId" to signal.strategyId,
                        "userId" to signal.userId,
                        "code" to signal.code,
                        "interval" to signal.interval.code,
                        "ts" to signal.ts.epochSeconds,
                        "side" to signal.side.name,
                        "strength" to signal.strength,
                        "reason" to signal.reason,
                        "priceAtSignal" to signal.priceAtSignal.toPlainString(),
                        "contextJson" to signal.contextJson,
                        "createdAt" to signal.createdAt?.toString(),
                    ),
                )
            kafkaTemplate.send(TOPIC, signal.userId, payload)
            log.debug(
                "SignalKafkaProducer publish: topic={} userId={} code={} ts={} side={}",
                TOPIC,
                signal.userId,
                signal.code,
                signal.ts,
                signal.side,
            )
        } catch (e: Exception) {
            log.error(
                "SignalKafkaProducer publish 실패: userId={} code={} err={}",
                signal.userId,
                signal.code,
                e.message,
            )
        }
    }
}
