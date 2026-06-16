package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * `account.balance.changed.v1` Kafka publish 어댑터.
 *
 * Polling → STOMP push 전환 (2026-05-20) — dogfooding evidence:
 *  - 기존: Dashboard 5s polling × (REAL+MOCK) → KIS 호출 burst → EGW00201 "초당 거래건수 초과"
 *  - 신규: 잔고 변경 이벤트(체결/취소/거절) 발생 시점에만 push → frontend invalidateQueries trigger
 *         → cache miss 1회만 발생 → KIS 호출 빈도 ↓↓
 *
 * ### publish 호출 site (3건)
 *  - [KisExecutionWsListener.handleExecution] 체결 완료 (FILLED·PARTIALLY_FILLED) → `ORDER_FILLED`
 *  - [KisExecutionWsListener.handleCancelled] 취소 접수 → `ORDER_CANCELLED`
 *  - [OrderRouterKafkaListener] Step 3·4·7 REJECTED 분기 → `ORDER_REJECTED`
 *
 * ### Kafka payload schema (LOCK)
 * ```json
 * {
 *   "userId": "74683223",
 *   "accountType": "REAL" | "MOCK",
 *   "reason": "ORDER_FILLED" | "ORDER_CANCELLED" | "ORDER_REJECTED",
 *   "triggerKisOrderNo": "0000032846",
 *   "occurredAt": "2026-05-20T15:11:55Z"
 * }
 * ```
 *
 * partition key = userId (per-user FIFO 보장).
 *
 * ### 운영 가드
 *  - publish 실패는 graceful log.error + swallow — 자본 흐름 본체(체결 영속·STOMP order push)는 이미 완료된 상태에서
 *    잔고 새로고침 trigger만 누락. 사용자는 다음 체결 이벤트 또는 30s fallback polling으로 회복 가능.
 *  - reason 값은 sealed-like enum 문자열(`ORDER_FILLED`·`ORDER_CANCELLED`·`ORDER_REJECTED`) — frontend가 무시 가능한
 *    discriminator (현 단계 frontend는 receive 즉시 invalidateQueries 호출).
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
@Component
class AccountBalanceChangedKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(AccountBalanceChangedKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Order.ACCOUNT_BALANCE_CHANGED

        const val REASON_FILLED = "ORDER_FILLED"
        const val REASON_CANCELLED = "ORDER_CANCELLED"
        const val REASON_REJECTED = "ORDER_REJECTED"
    }

    /**
     * 잔고 변경 이벤트 publish.
     *
     * @param userId            KIS HTS 사용자 ID (partition key)
     * @param mode              TradingMode (REAL/MOCK) — payload `accountType` 으로 직렬화 (same ordinal name)
     * @param reason            ORDER_FILLED · ORDER_CANCELLED · ORDER_REJECTED 중 하나 (companion 상수 사용)
     * @param triggerKisOrderNo 변경 trigger KIS 주문 번호 (audit 추적용 — nullable)
     */
    fun publish(
        userId: String,
        mode: TradingMode,
        reason: String,
        triggerKisOrderNo: String?,
    ) {
        val payload =
            try {
                objectMapper.writeValueAsString(
                    mapOf(
                        "userId" to userId,
                        "accountType" to mode.name,
                        "reason" to reason,
                        "triggerKisOrderNo" to triggerKisOrderNo,
                        "occurredAt" to Instant.now(clock).toString(),
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "AccountBalanceChangedKafkaProducer payload 직렬화 실패: userId={} reason={} err={}",
                    maskUserId(userId),
                    reason,
                    e.message,
                )
                return
            }

        try {
            kafkaTemplate.send(TOPIC, userId, payload)
            log.info(
                "AccountBalanceChangedKafkaProducer publish: topic={} userId={} accountType={} reason={} odno={}",
                TOPIC,
                maskUserId(userId),
                mode.name,
                reason,
                triggerKisOrderNo,
            )
        } catch (e: Exception) {
            // graceful — 자본 흐름 본체는 이미 완료. 잔고 새로고침 trigger만 누락 (사용자 fallback polling으로 회복).
            log.error(
                "AccountBalanceChangedKafkaProducer publish 실패 (graceful swallow): userId={} reason={} err={}",
                maskUserId(userId),
                reason,
                e.message,
            )
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
