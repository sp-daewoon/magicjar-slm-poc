package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.notification.NotificationFormatter
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * order.*.v1.DLT 4 토픽 모니터링 (ADR 0084 P0-4 — spec §2-4 §C LOCK).
 *
 * ### 외부 코드 리뷰 #7 흡수
 * DefaultErrorHandler(FixedBackOff 1s × 3회) 재시도 후 DLT publish 발사 → 본 listener가 P0 알림 즉시 발사.
 *
 * ### 4 DLT 토픽
 * | # | DLT topic                  | 원본 listener                    | 자본 path 영향 |
 * |---|----------------------------|----------------------------------|---------------|
 * | 1 | order.intent.v1.DLT        | OrderRouterKafkaListener (자본) | ★★★ (주문 자체 미발사)
 * | 2 | order.requested.v1.DLT     | STOMP fanout · Notification     | ★★  (사용자 알림 누락)
 * | 3 | order.rejected.v1.DLT      | STOMP fanout · Notification     | ★★  (거절 알림 누락)
 * | 4 | order.filled.v1.DLT        | STOMP fanout · Notification     | ★★★ (체결 알림 누락 — 자본 잠재)
 *
 * ### 처리 흐름
 * 1. DLT record 수신
 * 2. NotificationOutbox INSERT (channel=SLACK, priority=P0, eventType=DLT_RECEIVED)
 * 3. SlackWebhookSender 직접 발송 (P0 동기 — 운영자 즉시 인지)
 * 4. payload는 원본 record value를 그대로 보존 (DLT replay 검토용)
 *
 * ### NotificationOutbox graceful
 * outbox repository 미설정 시 → SlackWebhookSender 직접 발송만 (audit 영속 skip + log.warn).
 * SlackWebhookSender 미설정 시 → log.error 만 (운영자가 docker logs로 인지).
 *
 * ### partition·offset 보존
 * DeadLetterPublishingRecoverer가 원본 record headers에 src topic·partition·offset 자동 첨부.
 * 본 listener는 record.headers() 조회로 원본 추적 가능 (단, 본 fix에서는 단순화 — payload + topic 인용).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-8).
 */
@Component
class OrderDltMonitorListener(
    private val outboxRepositoryProvider: ObjectProvider<NotificationOutboxRepository>,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderDltMonitorListener::class.java)

    companion object {
        const val ORDER_INTENT_DLT = KafkaTopics.Dlt.ORDER_INTENT
        const val ORDER_REQUESTED_DLT = KafkaTopics.Dlt.ORDER_REQUESTED
        const val ORDER_REJECTED_DLT = KafkaTopics.Dlt.ORDER_REJECTED
        const val ORDER_FILLED_DLT = KafkaTopics.Dlt.ORDER_FILLED
        const val GROUP = "magicjar-consumer-dlt-monitor"
    }

    @KafkaListener(topics = [ORDER_INTENT_DLT], groupId = GROUP)
    fun onOrderIntentDlt(record: ConsumerRecord<String, String>) = handleDlt(record, ORDER_INTENT_DLT)

    @KafkaListener(topics = [ORDER_REQUESTED_DLT], groupId = GROUP)
    fun onOrderRequestedDlt(record: ConsumerRecord<String, String>) = handleDlt(record, ORDER_REQUESTED_DLT)

    @KafkaListener(topics = [ORDER_REJECTED_DLT], groupId = GROUP)
    fun onOrderRejectedDlt(record: ConsumerRecord<String, String>) = handleDlt(record, ORDER_REJECTED_DLT)

    @KafkaListener(topics = [ORDER_FILLED_DLT], groupId = GROUP)
    fun onOrderFilledDlt(record: ConsumerRecord<String, String>) = handleDlt(record, ORDER_FILLED_DLT)

    /**
     * 공통 DLT 처리 — outbox INSERT + Slack 즉시 발송.
     *
     * userId 추출 best-effort: payload JSON `userId` 필드 → 없으면 "unknown".
     */
    internal fun handleDlt(
        record: ConsumerRecord<String, String>,
        dltTopic: String,
    ) {
        try {
            val rawPayload = record.value() ?: "(empty payload)"
            val userId = extractUserId(rawPayload)
            val reason =
                "Kafka DLT 수신: topic=$dltTopic partition=${record.partition()} offset=${record.offset()} " +
                    "userId=${userId.take(4)}**** payload=${rawPayload.take(200)}"

            log.error(
                "OrderDltMonitorListener DLT 수신 (P0 알림): topic={} partition={} offset={} key={} userId={}****",
                dltTopic,
                record.partition(),
                record.offset(),
                record.key(),
                userId.take(4),
            )

            val eventId = UUID.randomUUID()
            val payloadJson =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to eventId.toString(),
                        "eventType" to NotificationEventType.DLT_RECEIVED.name,
                        "dltTopic" to dltTopic,
                        "srcPartition" to record.partition(),
                        "srcOffset" to record.offset(),
                        "srcKey" to record.key(),
                        "userId" to userId,
                        "rawPayload" to rawPayload.take(2000), // payload 최대 2KB 보존 (DLT replay 검토용)
                        "createdAt" to Instant.now().epochSecond,
                    ),
                )

            val outbox =
                NotificationOutbox(
                    eventId = eventId,
                    channel = NotificationChannel.SLACK,
                    priority = NotificationPriority.P0,
                    payloadJson = payloadJson,
                    status = NotificationStatus.PENDING,
                    createdAt = Instant.now(),
                )

            val outboxRepository = outboxRepositoryProvider.getIfAvailable()
            if (outboxRepository == null) {
                log.warn(
                    "OrderDltMonitorListener NotificationOutboxRepository 미등록 — outbox INSERT skip. dltTopic={}",
                    dltTopic,
                )
                tryDirectSlack(userId, reason)
                return
            }

            val saved = outboxRepository.save(outbox)
            log.info(
                "OrderDltMonitorListener outbox INSERT: id={} dltTopic={} userId={}****",
                saved.id,
                dltTopic,
                userId.take(4),
            )

            // P0 즉시 Slack 발송
            sendImmediate(saved, userId, reason, outboxRepository)
        } catch (e: Exception) {
            // DLT listener 자체 실패는 무한 loop 회피 — log.error 후 swallow (재처리 시도 0회)
            log.error(
                "OrderDltMonitorListener DLT 처리 자체 실패 (재처리 시도 0회): dltTopic={} offset={} err={}",
                dltTopic,
                record.offset(),
                e.message,
                e,
            )
        }
    }

    private fun extractUserId(rawPayload: String): String {
        if (rawPayload.isBlank() || rawPayload == "(empty payload)") return "unknown"
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(rawPayload, Map::class.java) as Map<String, Any?>
            map["userId"]?.toString() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun sendImmediate(
        outbox: NotificationOutbox,
        userId: String,
        reason: String,
        outboxRepository: NotificationOutboxRepository,
    ) {
        val sender = slackSenderProvider.getIfAvailable()
        if (sender == null) {
            log.warn(
                "OrderDltMonitorListener SlackWebhookSender 미등록 — P0 발송 skip, outbox PENDING 유지: userId={}****",
                userId.take(4),
            )
            return
        }
        val id = outbox.id ?: return
        try {
            val sent = sender.sendOutbox(outbox)
            if (sent) {
                outboxRepository.markSent(id)
                log.info("DLT P0 Slack 발송 성공: outboxId={} userId={}****", id, userId.take(4))
            } else {
                outboxRepository.incrementRetryCount(id)
                log.warn("DLT P0 Slack 발송 실패 — PENDING 유지: outboxId={} userId={}****", id, userId.take(4))
            }
        } catch (e: Exception) {
            log.error("DLT P0 Slack 발송 예외: outboxId={} err={}", id, e.message)
            outboxRepository.incrementRetryCount(id)
        }
    }

    private fun tryDirectSlack(
        userId: String,
        reason: String,
    ) {
        val sender = slackSenderProvider.getIfAvailable() ?: return
        try {
            val message = NotificationFormatter.format(userId, NotificationEventType.DLT_RECEIVED, reason, emptyMap())
            sender.sendDirect(message)
        } catch (e: Exception) {
            log.error("DLT direct Slack 발송 예외: userId={}**** err={}", userId.take(4), e.message)
        }
    }
}
