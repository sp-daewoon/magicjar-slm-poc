package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SignalResponse
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * `kafka.consumer.evt.strategy.signal-generated.v1` Kafka 토픽 → STOMP `/topic/stomp.api.signal.user.{userId}` fanout.
 *
 * Phase 1 consumer가 발행한 시그널을 Kafka에서 수신하여
 * 해당 사용자의 STOMP 구독 토픽으로 실시간 전달 (ADR 0066 §2-3 D-1).
 *
 * ### groupId
 * `magicjar-api-stomp-signal` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 `magicjar-signal-generator` 그룹과 분리.
 *
 * ### STOMP 토픽
 * `/topic/stomp.api.signal.user.{userId}` — 사용자별 fanout (userId는 payload userId 필드).
 *
 * ### 에러 처리
 * 역직렬화 실패 시 log.error + skip (메시지 손실 허용 — 시그널은 DB 재조회 가능).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class StompSignalDispatcher(
    private val brokerMessagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val stompEventPersister: StompEventPersister,
) {
    private val log = LoggerFactory.getLogger(StompSignalDispatcher::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Risk.SIGNAL_GENERATED],
        groupId = "magicjar-api-stomp-signal",
    )
    fun onSignalGenerated(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val rawMap = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val userId =
                rawMap["userId"]?.toString() ?: run {
                    log.warn("{} userId null — offset={}", KafkaTopics.Risk.SIGNAL_GENERATED, record.offset())
                    return
                }

            val payload = toSignalResponse(rawMap)
            val topic = StompDestinations.signalUser(userId)
            // ADR 0084 P1-2: publish 직전 stomp_event 영속 (best-effort)
            stompEventPersister.persist(
                userId = userId,
                channel = "signal",
                eventType = payload.side, // BUY · SELL · HOLD 등 side를 eventType으로 활용
                payload = payload,
            )
            brokerMessagingTemplate.convertAndSend(topic, payload)
            log.debug(
                "STOMP {} 발행: strategyId={} code={} side={}",
                topic,
                rawMap["strategyId"],
                rawMap["code"],
                rawMap["side"],
            )
        } catch (e: Exception) {
            log.error(
                "{} STOMP 포워드 실패: offset={} err={}",
                KafkaTopics.Risk.SIGNAL_GENERATED,
                record.offset(),
                e.message,
            )
        }
    }

    // --- private helper ---

    private fun toSignalResponse(raw: Map<String, Any?>): SignalResponse =
        SignalResponse(
            id = toLong(raw["id"]),
            strategyId = toLong(raw["strategyId"]),
            strategyName = raw["strategyName"]?.toString(),
            strategyNameKo = raw["strategyNameKo"]?.toString(),
            buyCondition = raw["buyCondition"]?.toString(),
            sellCondition = raw["sellCondition"]?.toString(),
            userId = raw["userId"]?.toString() ?: "",
            code = raw["code"]?.toString() ?: "",
            codeName = resolveCodeName(raw),
            interval = raw["interval"]?.toString() ?: "",
            ts = raw["ts"]?.toString() ?: "",
            side = raw["side"]?.toString() ?: "HOLD",
            strength = toInt(raw["strength"]),
            reason = raw["reason"]?.toString() ?: "",
            priceAtSignal = toBigDecimal(raw["priceAtSignal"]),
            contextJson = raw["contextJson"]?.toString(),
            createdAt = raw["createdAt"]?.toString(),
        )

    private fun resolveCodeName(raw: Map<String, Any?>): String? {
        val code = raw["code"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val payloadName = raw["codeName"]?.toString()?.takeIf { it.isNotBlank() && it != code }
        if (payloadName != null) return payloadName
        return runCatching { instrumentJpaRepository.findById(code).orElse(null) }
            .getOrNull()
            ?.takeIf { it.market != "UNKNOWN" }
            ?.takeIf { it.name.isNotBlank() && it.name != it.code }
            ?.name
    }

    private fun toLong(value: Any?): Long =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun toInt(value: Any?): Int =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }

    private fun toBigDecimal(value: Any?): BigDecimal =
        when (value) {
            is Number -> BigDecimal(value.toString())
            is String -> runCatching { BigDecimal(value) }.getOrDefault(BigDecimal.ZERO)
            else -> BigDecimal.ZERO
        }
}
