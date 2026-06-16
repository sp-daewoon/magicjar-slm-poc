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
 * Kafka `order.*.v1` 4 topic → STOMP `/topic/order.user.{userId}` fanout.
 * (T-O ADR 0068 §15-4 + 의제 G-1 단일 토픽 4 event discriminator 결정 정합)
 *
 * ### groupId
 * `magicjar-api-stomp-order` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 `magicjar-consumer-order` 그룹과 분리.
 *
 * ### STOMP 토픽
 * `/topic/order.user.{userId}` — 사용자별 fanout.
 *
 * ### 이벤트 type 4건 (의제 G-1 LOCK)
 * - `REQUESTED` — 주문 접수 (파랑)
 * - `FILLED`    — 체결 완료 (녹색), partial=true 시 부분 체결 (노랑)
 * - `REJECTED`  — 주문 거절 (빨강)
 * - `CANCELLED` — 주문 취소 (회색)
 *
 * ### 에러 처리
 * 역직렬화 실패 시 log.error + skip (손실 허용 — DB order_request 재조회 가능).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class StompOrderNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
    private val stompEventPersister: StompEventPersister,
) {
    private val log = LoggerFactory.getLogger(StompOrderNotifier::class.java)

    @KafkaListener(
        topics = [
            KafkaTopics.Order.REQUESTED,
            KafkaTopics.Order.FILLED,
            KafkaTopics.Order.REJECTED,
            KafkaTopics.Order.CANCELLED,
        ],
        groupId = "magicjar-api-stomp-order",
    )
    fun onOrderEvent(
        record: ConsumerRecord<String, String>,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
    ) {
        try {
            val eventType =
                when (topic) {
                    KafkaTopics.Order.REQUESTED -> "REQUESTED"
                    KafkaTopics.Order.FILLED -> "FILLED"
                    KafkaTopics.Order.REJECTED -> "REJECTED"
                    KafkaTopics.Order.CANCELLED -> "CANCELLED"
                    else -> {
                        log.warn("알 수 없는 order 이벤트 토픽: {}", topic)
                        return
                    }
                }

            @Suppress("UNCHECKED_CAST")
            val rawMap = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val userId =
                rawMap["userId"]?.toString() ?: run {
                    log.warn("order event userId null — topic={} offset={}", topic, record.offset())
                    return
                }

            val payload = toStompPayload(rawMap, eventType)
            val destination = StompDestinations.orderUser(userId)
            // ADR 0084 P1-2: publish 직전 stomp_event 영속 (best-effort)
            stompEventPersister.persist(
                userId = userId,
                channel = "order",
                eventType = eventType,
                payload = payload,
            )
            messagingTemplate.convertAndSend(destination, payload)

            log.debug(
                "STOMP {} 발행: event={} orderId={} code={} side={}",
                destination,
                eventType,
                payload.orderId,
                payload.code,
                payload.side,
            )
        } catch (e: Exception) {
            log.error(
                "order event STOMP 포워드 실패: topic={} offset={} err={}",
                topic,
                record.offset(),
                e.message,
            )
        }
    }

    // --- private helper ---

    /**
     * hotfix(2026-05-20): publisher contract drift defensive read.
     *   - executedQty ← raw["executedQty"] ?: raw["executedQuantity"] (옛 FILLED key)
     *   - rejectReason ← raw["rejectReason"] ?: raw["reason"] (옛 REJECTED key)
     *   outbox에 잔존한 옛 payload replay·in-flight Kafka msg lag 시 frontend "0주" 회피.
     */
    private fun toStompPayload(
        raw: Map<String, Any?>,
        eventType: String,
    ): OrderStompPayload =
        OrderStompPayload(
            event = eventType,
            orderId = toLong(raw["orderId"]),
            userId = raw["userId"]?.toString() ?: "",
            code = raw["code"]?.toString(),
            side = raw["side"]?.toString(),
            qty = toInt(raw["quantity"]),
            priceType = raw["priceType"]?.toString(),
            limitPrice = raw["limitPrice"]?.toString(),
            executedPrice = raw["executedPrice"]?.toString(),
            executedQty = toInt(raw["executedQty"] ?: raw["executedQuantity"]),
            partial = raw["partial"] as? Boolean ?: false,
            rejectReason = raw["rejectReason"]?.toString() ?: raw["reason"]?.toString(),
            mode = raw["mode"]?.toString(),
            ts =
                raw["ts"]?.toString() ?: java.time.Instant
                    .now()
                    .toString(),
        )

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
 * 주문 이벤트 STOMP 전달 DTO (ADR 0068 §15-4 + 의제 G-1 4 event discriminator).
 *
 * event: REQUESTED(파랑) · FILLED(녹색) · REJECTED(빨강) · CANCELLED(회색)
 * partial=true 시 부분 체결 (FILLED 노랑 variant)
 */
@Schema(description = "주문 이벤트 STOMP 페이로드 (/topic/order.user.{userId})")
data class OrderStompPayload(
    @field:Schema(description = "이벤트 유형", example = "FILLED", allowableValues = ["REQUESTED", "FILLED", "REJECTED", "CANCELLED"])
    val event: String,
    @field:Schema(description = "주문 ID", example = "1")
    val orderId: Long?,
    @field:Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드", example = "005930")
    val code: String?,
    @field:Schema(description = "매수/매도", example = "BUY")
    val side: String?,
    @field:Schema(description = "주문 수량", example = "10")
    val qty: Int?,
    @field:Schema(description = "가격 유형", example = "MARKET")
    val priceType: String?,
    @field:Schema(description = "지정가 (LIMIT 시만)", example = "73000")
    val limitPrice: String?,
    @field:Schema(description = "체결 단가 (FILLED 시)", example = "73000")
    val executedPrice: String?,
    @field:Schema(description = "체결 수량 (FILLED 시)", example = "10")
    val executedQty: Int?,
    @field:Schema(description = "부분 체결 여부 (FILLED event 내 구분)", example = "false")
    val partial: Boolean = false,
    @field:Schema(description = "거절 사유 (REJECTED 시)", example = "CONSENT_NOT_GIVEN")
    val rejectReason: String?,
    @field:Schema(description = "거래 모드", example = "MOCK")
    val mode: String?,
    @field:Schema(description = "이벤트 발생 시각 (ISO-8601 UTC)", example = "2026-05-13T09:00:01Z")
    val ts: String,
)
