package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.text.DecimalFormat
import java.time.Instant
import java.util.UUID

/**
 * T-NOTIFY 알림 Kafka 7 listener 통합 dispatcher (ADR 0044 + ADR 0070 §2-4 + spec §4 LOCK).
 *
 * ### 7 listen 매트릭스 (ADR 0070 §2-4)
 * | # | topic                  | priority | rate limit             |
 * |---|------------------------|----------|------------------------|
 * | 1 | order.filled.v1        | P0       | 즉시 (TRADE, REAL/MOCK/PAPER 공통) |
 * | 2 | order.rejected.v1      | P0       | 즉시 (TRADE, REAL/MOCK/PAPER 공통) |
 * | 3 | order.cancelled.v1     | P2       | 60분 윈도 ≥ 5건 시 1회   |
 * | 4 | risk.violation.v1      | P0       | 즉시, 반복 보호 차단은 사용자·종목·규칙별 15분 dedupe |
 * | 5 | reconcile.drift.v1     | P0       | 즉시                     |
 * | 6 | killswitch.engaged.v1  | P0       | 즉시                     |
 * | 7 | reconcile.run.v1       | P2       | 즉시 (audit only)        |
 *
 * ### 처리 흐름
 * Kafka listen → buildPayload → dedup check → rate limit check → outbox INSERT (PENDING)
 * → SlackWebhookSender 동기 발송 (P0) or deferred (P1/P2)
 *
 * ### outbox graceful
 * NotificationOutboxRepository 미설정 시 log.warn + skip (Phase 2 JPA 합류 전 graceful).
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class NotificationDispatcher(
    private val outboxRepositoryProvider: ObjectProvider<NotificationOutboxRepository>,
    private val rateLimiterProvider: ObjectProvider<NotificationRateLimiterPort>,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val instrumentRepositoryProvider: ObjectProvider<ConsumerInstrumentJpaRepository>,
    private val strategyRepositoryProvider: ObjectProvider<ConsumerStrategyJpaRepository>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(NotificationDispatcher::class.java)

    companion object {
        // P2 rate limit 임계값 (ADR 0070 §2-4)
        const val CANCELLED_THRESHOLD = 5

        const val WINDOW_60_MIN = 60

        const val MAX_RETRY_COUNT = 5

        const val RISK_REPEAT_COOLDOWN_WINDOW_MIN = 15

        private val RISK_REPEAT_COOLDOWN_RULES = setOf("POSITION_LIMIT", "SELL_WITHOUT_POSITION")

        /**
         * Slack 발송 제외 리스크 룰 set (2026-06-12 최고 관리자 결재).
         *
         * 진보 페르소나 (ADR 0087):
         *   - 💡 보유 0 매도 차단(`SELL_WITHOUT_POSITION`)은 order intent·KIS 접수 이전 단계에서 종결되는
         *     **확정적 가드** — 사용자 행동을 요하지 않으므로 Slack 알림 불요(오늘 62건 차단 → 주문 0건).
         *   - ✨ 하드코딩 1종 제외 대신 set 상수화 — 향후 동일 성격 룰 추가 시 한 곳만 보강(과개발 회피).
         *   - 제외는 **Slack 채널 한정** — `risk_event` DB 기록·Kafka·STOMP 화면 표시는 별도 경로
         *     (ConsumerRiskEventJpaAdapter·RiskViolationKafkaProducer·StompRiskNotifier)에서 유지(감사 추적 보존).
         */
        private val SLACK_MUTED_RISK_RULES = setOf("SELL_WITHOUT_POSITION")
    }

    // --- 7 @KafkaListener ---

    @KafkaListener(topics = [KafkaTopics.Order.FILLED], groupId = "magicjar-notification-order-filled")
    fun onOrderFilled(record: ConsumerRecord<String, String>) = handleEvent(record, NotificationEventType.ORDER_FILLED, "order.filled")

    @KafkaListener(topics = [KafkaTopics.Order.REJECTED], groupId = "magicjar-notification-order-rejected")
    fun onOrderRejected(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.ORDER_REJECTED, "order.rejected")

    @KafkaListener(topics = [KafkaTopics.Order.CANCELLED], groupId = "magicjar-notification-order-cancelled")
    fun onOrderCancelled(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.ORDER_CANCELLED, "order.cancelled")

    @KafkaListener(topics = [KafkaTopics.Risk.VIOLATION], groupId = "magicjar-notification-risk-violation")
    fun onRiskViolation(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.RISK_VIOLATION, "risk.violation")

    @KafkaListener(topics = [KafkaTopics.Reconcile.DRIFT], groupId = "magicjar-notification-reconcile-drift")
    fun onReconcileDrift(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.RECONCILE_DRIFT, "reconcile.drift")

    @KafkaListener(topics = [KafkaTopics.Risk.KILLSWITCH_ENGAGED], groupId = "magicjar-notification-killswitch")
    fun onKillSwitchEngaged(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.KILLSWITCH_ENGAGED, "killswitch.engaged")

    @KafkaListener(topics = [KafkaTopics.Reconcile.RUN], groupId = "magicjar-notification-reconcile-run")
    fun onReconcileRun(record: ConsumerRecord<String, String>) = handleEvent(record, NotificationEventType.RECONCILE_RUN, "reconcile.run")

    // --- 공통 처리 ---

    internal fun handleEvent(
        record: ConsumerRecord<String, String>,
        eventType: NotificationEventType,
        topicHash: String,
    ) {
        try {
            val value = record.value() ?: return

            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>

            val userId =
                raw["userId"]?.toString() ?: run {
                    log.warn("NotificationDispatcher userId null — topic={} offset={}", record.topic(), record.offset())
                    return
                }

            // eventId dedup: 수신한 eventId or 신규 생성
            val eventId =
                raw["eventId"]?.toString()?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                } ?: UUID.randomUUID()
            val enrichedRaw = enrichStrategy(enrichInstrument(raw))

            // P1/P2 rate limit 체크
            if (!shouldEmit(userId, topicHash, eventType.defaultPriority, eventType, enrichedRaw)) {
                log.debug(
                    "NotificationDispatcher rate limit skip — userId={} type={} topicHash={}",
                    userId.take(4) + "****",
                    eventType,
                    topicHash,
                )
                return
            }

            val reason = buildReason(enrichedRaw, eventType)
            val meta = buildMeta(enrichedRaw, eventType)
            val payloadJson =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to eventId.toString(),
                        "eventType" to eventType.name,
                        "userId" to userId,
                        "reason" to reason,
                        "meta" to meta,
                        "createdAt" to Instant.now().epochSecond,
                    ),
                )

            val outbox =
                NotificationOutbox(
                    eventId = eventId,
                    channel = NotificationChannel.SLACK,
                    priority = eventType.defaultPriority,
                    payloadJson = payloadJson,
                    status = NotificationStatus.PENDING,
                    createdAt = Instant.now(),
                )

            val outboxRepository = outboxRepositoryProvider.getIfAvailable()
            if (outboxRepository == null) {
                log.warn("NotificationOutboxRepository 미등록 — outbox INSERT skip. topic={} userId={}****", record.topic(), userId.take(4))
                // P0는 outbox 없어도 직접 Slack 시도
                if (eventType.defaultPriority == NotificationPriority.P0) {
                    tryDirectSlack(userId, reason, eventType, meta)
                }
                return
            }

            val saved = outboxRepository.save(outbox)
            log.info(
                "NotificationDispatcher outbox INSERT: id={} type={} priority={} userId={}****",
                saved.id,
                eventType,
                eventType.defaultPriority,
                userId.take(4),
            )

            // P0: 즉시 Slack 발송 시도
            if (eventType.defaultPriority == NotificationPriority.P0) {
                sendImmediate(saved, userId, reason, outboxRepository)
            }

            // ORDER_FILLED 추가 채널 이중 송출 (2026-06-10 최고 관리자 지시 · V79).
            // 기존 채널 발송/outbox 상태와 완전 격리 — 실패해도 위 흐름에 영향 0.
            if (eventType == NotificationEventType.ORDER_FILLED) {
                sendOrderFilledToAdditionalChannel(userId, reason, eventType, meta)
            }
        } catch (e: Exception) {
            log.error(
                "NotificationDispatcher 처리 실패: topic={} offset={} err={}",
                record.topic(),
                record.offset(),
                e.message,
                e,
            )
        }
    }

    // --- private helpers ---

    private fun shouldEmit(
        userId: String,
        topicHash: String,
        priority: NotificationPriority,
        eventType: NotificationEventType,
        raw: Map<String, Any?>,
    ): Boolean {
        if (eventType == NotificationEventType.RISK_VIOLATION && isSlackMutedRisk(raw)) {
            // Slack 채널만 제외 — DB·Kafka·STOMP 는 별도 경로에서 이미 처리됨(감사 추적 보존).
            log.debug(
                "NotificationDispatcher Slack 제외 룰 — userId={} rule={}",
                userId.take(4) + "****",
                raw["rule"],
            )
            return false
        }
        if (eventType == NotificationEventType.RISK_VIOLATION && isCooldownRisk(raw)) {
            val rateLimiter = rateLimiterProvider.getIfAvailable() ?: return true
            val cooldownKey = riskCooldownTopicHash(raw)
            return rateLimiter.shouldEmitOncePerWindow(userId, cooldownKey, RISK_REPEAT_COOLDOWN_WINDOW_MIN)
        }
        if (priority == NotificationPriority.P0) return true

        val rateLimiter = rateLimiterProvider.getIfAvailable() ?: return true // graceful: limiter 미등록 시 허용

        val (threshold, windowMin) =
            when (topicHash) {
                "order.cancelled" -> CANCELLED_THRESHOLD to WINDOW_60_MIN
                else -> 1 to WINDOW_60_MIN
            }
        return rateLimiter.shouldEmit(userId, topicHash, threshold, windowMin)
    }

    private fun sendImmediate(
        outbox: NotificationOutbox,
        userId: String,
        reason: String,
        outboxRepository: NotificationOutboxRepository,
    ) {
        val sender = slackSenderProvider.getIfAvailable()
        if (sender == null) {
            log.warn("SlackWebhookSender 미등록 — P0 즉시 발송 skip, outbox PENDING 유지: userId={}****", userId.take(4))
            return
        }
        val id = outbox.id ?: return
        try {
            val sent = sender.sendOutbox(outbox)
            if (sent) {
                outboxRepository.markSent(id)
                log.info("P0 즉시 Slack 발송 성공: outboxId={} userId={}****", id, userId.take(4))
            } else {
                outboxRepository.incrementRetryCount(id)
                log.warn("P0 즉시 Slack 발송 실패 — PENDING 유지: outboxId={} userId={}****", id, userId.take(4))
            }
        } catch (e: Exception) {
            log.error("P0 즉시 Slack 발송 예외: outboxId={} err={}", id, e.message)
            outboxRepository.incrementRetryCount(id)
        }
    }

    private fun tryDirectSlack(
        userId: String,
        reason: String,
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ) {
        val sender = slackSenderProvider.getIfAvailable() ?: return
        try {
            val message = NotificationFormatter.format(userId, eventType, reason, meta)
            sender.sendDirect(message)
        } catch (e: Exception) {
            log.error("direct Slack 발송 예외: userId={}**** err={}", userId.take(4), e.message)
        }
    }

    /**
     * 주문 체결(ORDER_FILLED) 추가 Slack 채널 이중 송출 (2026-06-10 최고 관리자 지시 · V79).
     *
     * 진보 페르소나 (ADR 0087):
     *   - ✨ 기존 채널 발송·outbox 상태와 **완전 격리** — runCatching 으로 어떤 예외도 caller 흐름에 전파 안 함.
     *   - 💡 추가 채널(notification_slack_webhook_order) 미설정 시 sender 가 silent skip → 기존 흐름 영향 0.
     *   - 동일 메시지(NotificationFormatter)로 기존 채널과 1:1 동등 본문 발송.
     */
    private fun sendOrderFilledToAdditionalChannel(
        userId: String,
        reason: String,
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ) {
        val sender = slackSenderProvider.getIfAvailable() ?: return
        runCatching {
            val message = NotificationFormatter.format(userId, eventType, reason, meta)
            sender.sendToOrderChannel(message)
        }.onFailure { e ->
            // 격리: 추가 채널 실패가 기존 채널/outbox 에 영향 0 — 로그만 남김.
            log.warn("주문 추가 Slack 채널 이중 송출 예외 (기존 채널 영향 0): userId={}**** err={}", userId.take(4), e.message)
        }
    }

    private fun buildReason(
        raw: Map<String, Any?>,
        eventType: NotificationEventType,
    ): String =
        when (eventType) {
            NotificationEventType.ORDER_FILLED ->
                listOf(
                    "체결:",
                    "${accountModeLabel(raw)}${instrumentLabel(raw)}",
                    sideKorean(raw["side"]),
                    "${executedQuantity(raw)}주",
                    "@ ${formatWon(raw["executedPrice"])}",
                ).joinToString(" ")
            NotificationEventType.ORDER_REJECTED ->
                "주문 거절: ${accountModeLabel(raw)}${instrumentLabel(raw)} ${sideKorean(raw["side"])} — ${rejectReason(raw)}"
            NotificationEventType.ORDER_CANCELLED ->
                "주문 취소: ${accountModeLabel(raw)}${instrumentLabel(raw)} ${sideKorean(raw["side"])} (주문번호 ${raw["kisOrderNo"]})"
            NotificationEventType.RISK_VIOLATION ->
                buildRiskReason(raw)
            NotificationEventType.RECONCILE_DRIFT ->
                // 2026-06-05 알림 친절도 fix — internalQty 는 drift 검출 시점(보정 직전) 스냅샷이다.
                // reconcile 은 KIS 기준(kisQty)으로 내부 position 을 upsert 하므로 보정 후 내부 = KIS 수량이 된다.
                // "보정 전 내부=N → 보정 후 내부=M(KIS)" 로 표기해 사용자가 스냅샷/보정 결과를 혼동하지 않게 한다.
                "포지션 불일치: ${raw["symbol"]} KIS=${raw["kisQty"]}주 " +
                    "(보정 전 내부=${raw["internalQty"]}주 → 보정 후 내부=${raw["kisQty"]}주) diff=${raw["qtyDiff"]}"
            NotificationEventType.KILLSWITCH_ENGAGED ->
                "KillSwitch 발동: reason=${raw["reason"]} triggeredBy=${raw["triggeredBy"]}"
            NotificationEventType.RECONCILE_RUN ->
                "잔고 점검 완료: status=${raw["status"]} driftCount=${raw["driftCount"]}"
            NotificationEventType.DLT_RECEIVED ->
                // 본 dispatcher가 직접 produce 안 함 — OrderDltMonitorListener 영역.
                // exhaustive when 정합용 분기 (실제 호출 path 0건).
                "Kafka DLT 수신: dltTopic=${raw["dltTopic"]} srcOffset=${raw["srcOffset"]}"
            NotificationEventType.SYSTEM_EVENT ->
                "시스템 이벤트: severity=${raw["severity"]} category=${raw["category"]} action=${raw["action"]} detail=${raw["detailJson"]}"
        }

    private fun accountModeLabel(raw: Map<String, Any?>): String {
        val mode = raw["accountType"] ?: raw["mode"] ?: raw["accountMode"]
        return mode?.toString()?.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""
    }

    private fun executedQuantity(raw: Map<String, Any?>): Any? =
        raw["executedQuantity"]
            ?: raw["executedQty"]
            ?: raw["filledQuantity"]
            ?: raw["filledQty"]

    private fun rejectReason(raw: Map<String, Any?>): Any? =
        raw["reason"]
            ?: raw["rejectReason"]
            ?: raw["errorMessage"]
            ?: raw["message"]

    private fun buildMeta(
        raw: Map<String, Any?>,
        eventType: NotificationEventType,
    ): Map<String, String?> = raw.mapKeys { it.key }.mapValues { it.value?.toString() }

    private fun buildRiskReason(raw: Map<String, Any?>): String {
        val code = instrumentLabel(raw).takeIf { it != "?" }?.let { "$it " }.orEmpty()
        val title = raw["alertTitle"]?.toString()?.takeIf { it.isNotBlank() }
        val message = raw["alertMessage"]?.toString()?.takeIf { it.isNotBlank() }
        return if (title != null || message != null) {
            "리스크 차단: $code${title ?: raw["rule"]} — ${message ?: raw["reason"]}"
        } else {
            "리스크 위반: ${raw["code"]} rule=${raw["rule"]} — ${raw["reason"]}"
        }
    }

    private fun enrichInstrument(raw: Map<String, Any?>): Map<String, Any?> {
        val code = codeOf(raw) ?: return raw
        if (!raw["instrumentName"]?.toString().isNullOrBlank()) return raw
        val repository = instrumentRepositoryProvider.getIfAvailable() ?: return raw
        val name =
            runCatching { repository.findById(code).orElse(null) }
                .getOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() && it != code }
                ?: return raw
        return raw + ("instrumentName" to name)
    }

    private fun enrichStrategy(raw: Map<String, Any?>): Map<String, Any?> {
        val strategyId = raw["strategyId"]?.toString()?.toLongOrNull()?.takeIf { it > 0L } ?: return raw
        if (!raw["strategyName"]?.toString().isNullOrBlank()) return raw
        val repository = strategyRepositoryProvider.getIfAvailable() ?: return raw
        val strategy =
            runCatching { repository.findById(strategyId).orElse(null) }
                .getOrNull()
                ?: return raw
        val displayName =
            strategy.nameKo
                ?.takeIf { it.isNotBlank() }
                ?: strategy.name.takeIf { it.isNotBlank() }
                ?: return raw
        return raw + ("strategyName" to displayName)
    }

    private fun codeOf(raw: Map<String, Any?>): String? =
        raw["code"]?.toString()?.takeIf { it.isNotBlank() }
            ?: raw["symbol"]?.toString()?.takeIf { it.isNotBlank() }

    private fun instrumentLabel(raw: Map<String, Any?>): String {
        val code = codeOf(raw)
        val name = raw["instrumentName"]?.toString()?.takeIf { it.isNotBlank() && it != code }
        return when {
            name != null && code != null -> "$name($code)"
            code != null -> code
            name != null -> name
            else -> "?"
        }
    }

    private fun sideKorean(side: Any?): String =
        when (side?.toString()?.uppercase()) {
            "BUY" -> "매수"
            "SELL" -> "매도"
            else -> side?.toString() ?: "?"
        }

    private fun formatWon(value: Any?): String {
        if (value == null) return "?원"
        val formatted =
            value
                .toString()
                .toBigDecimalOrNull()
                ?.let { DecimalFormat("#,##0.####").format(it) }
                ?: value.toString()
        return "${formatted}원"
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    private fun isCooldownRisk(raw: Map<String, Any?>): Boolean =
        raw["rule"]
            ?.toString()
            ?.uppercase()
            ?.let { it in RISK_REPEAT_COOLDOWN_RULES }
            ?: false

    private fun isSlackMutedRisk(raw: Map<String, Any?>): Boolean =
        raw["rule"]
            ?.toString()
            ?.uppercase()
            ?.let { it in SLACK_MUTED_RISK_RULES }
            ?: false

    private fun riskCooldownTopicHash(raw: Map<String, Any?>): String =
        listOf(
            "risk.violation",
            raw["rule"]?.toString()?.lowercase().orEmpty(),
            codeOf(raw).orEmpty(),
            raw["strategyId"]?.toString().orEmpty(),
        ).joinToString(".")
}
