package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Kafka `account.balance.changed.v1` → STOMP `/topic/account.balance.{userId}` fanout.
 *
 * Polling → STOMP push 전환 backend 출구점 (2026-05-20).
 *
 * ### groupId
 * `magicjar-account-stomp` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 발행 path와 grup 분리 (offset 독립).
 *
 * ### STOMP 토픽
 * `/topic/account.balance.{userId}` — 사용자별 fanout.
 *
 * ### payload schema
 * ```json
 * {
 *   "accountType": "REAL" | "MOCK",
 *   "reason": "ORDER_FILLED" | "ORDER_CANCELLED" | "ORDER_REJECTED",
 *   "occurredAt": "2026-05-20T15:11:55Z"
 * }
 * ```
 *
 * frontend는 메시지 수신 즉시 React Query `invalidateQueries(["account", "balance"])` trigger →
 * Dashboard 잔고 cache miss → 1회 REST 호출 (cache TTL 10s 정합).
 *
 * ### 에러 처리
 * 역직렬화 실패 시 log.error + skip (손실 허용 — 30s fallback polling으로 회복).
 *
 * ### stomp_event 영속
 * ADR 0084 P1-2 정합 — [StompEventPersister] 호출로 frontend 재접속 시 backfill 가능.
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
@Component
class StompAccountNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
    private val stompEventPersister: StompEventPersister,
) {
    private val log = LoggerFactory.getLogger(StompAccountNotifier::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.ACCOUNT_BALANCE_CHANGED
        const val GROUP = "magicjar-account-stomp"
        const val CHANNEL = "account"
        const val EVENT_TYPE_BALANCE_CHANGED = "BALANCE_CHANGED"
    }

    @KafkaListener(
        topics = [TOPIC],
        groupId = GROUP,
    )
    fun onAccountBalanceChanged(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val rawMap = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val userId =
                rawMap["userId"]?.toString() ?: run {
                    log.warn("account.balance.changed userId null — offset={}", record.offset())
                    return
                }

            val payload =
                AccountBalanceChangedStompPayload(
                    accountType = RuntimeModeParser.accountTypeOrMock(rawMap["accountType"]?.toString()).name,
                    reason = rawMap["reason"]?.toString() ?: "UNKNOWN",
                    occurredAt = rawMap["occurredAt"]?.toString() ?: Instant.now().toString(),
                )

            val destination = StompDestinations.accountBalanceUser(userId)
            // ADR 0084 P1-2: publish 직전 stomp_event 영속 (best-effort)
            stompEventPersister.persist(
                userId = userId,
                channel = CHANNEL,
                eventType = EVENT_TYPE_BALANCE_CHANGED,
                payload = payload,
            )
            messagingTemplate.convertAndSend(destination, payload)

            log.debug(
                "STOMP {} 발행: accountType={} reason={}",
                destination,
                payload.accountType,
                payload.reason,
            )
        } catch (e: Exception) {
            log.error(
                "account.balance.changed STOMP 포워드 실패: offset={} err={}",
                record.offset(),
                e.message,
            )
        }
    }
}

/**
 * 계좌 잔고 변경 STOMP 페이로드 — frontend invalidateQueries trigger 용.
 *
 * `triggerKisOrderNo` 등 audit 필드는 backend Kafka payload에만 보존 (frontend 노출 불필요).
 */
@Schema(description = "계좌 잔고 변경 STOMP 페이로드 (/topic/account.balance.{userId})")
data class AccountBalanceChangedStompPayload(
    @field:Schema(description = "계좌 유형", example = "REAL", allowableValues = ["REAL", "MOCK"])
    val accountType: String,
    @field:Schema(
        description = "변경 사유",
        example = "ORDER_FILLED",
        allowableValues = ["ORDER_FILLED", "ORDER_CANCELLED", "ORDER_REJECTED"],
    )
    val reason: String,
    @field:Schema(description = "이벤트 발생 시각 (ISO-8601 UTC)", example = "2026-05-20T15:11:55Z")
    val occurredAt: String,
)
