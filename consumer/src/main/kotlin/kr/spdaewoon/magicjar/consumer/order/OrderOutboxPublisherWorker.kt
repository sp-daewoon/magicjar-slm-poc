package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * order_event_outbox PENDING 재시도 worker (ADR 0084 P1-1 + spec 2026-05-18 §2-5 LOCK).
 *
 * ### 책임
 * - 4 producer (Intent·Requested·Rejected·Filled)가 Kafka publish 실패 시 잔존시킨 outbox row 비동기 retry
 * - attempt_count ≥ MAX_ATTEMPT 시 FAILED 전환 + P0 운영 알림 + KillSwitch auto-ON (자본 path 보호 — ADR 0043 정합)
 *
 * ### 스케줄
 * `@Scheduled(fixedDelay = 5_000)` — 5초 주기 (spec §2-5 본문 권장).
 * fixedDelay 사용: 직전 실행 완료 후 5초 대기 (동시 실행 방지).
 *
 * ### 처리 흐름
 * 1. findPending(limit=100) — published_at IS NULL · 오래된 순
 * 2. 각 row → kafkaTemplate.send(topic, partitionKey, payloadJson)
 * 3. 성공: markPublished
 * 4. 실패: attempt_count++ + last_error 갱신
 *    attempt_count ≥ MAX_ATTEMPT(3) → markFailed + 운영 알림 + KillSwitch engage
 *
 * ### 트랜잭션 경계 (FIX C-2)
 * `@Transactional` 로 `findPending(FOR UPDATE SKIP LOCKED) → send.get(timeout) → markPublished/recordAttemptFailure`
 * 까지 단일 트랜잭션 묶음. 다중 worker instance 동시 실행 시 같은 row 중복 처리 영구 차단.
 *
 * ### Kafka publish timeout (FIX C-1)
 * `kafkaTemplate.send().get([KAFKA_SEND_TIMEOUT_SECONDS]s)` — broker 응답 지연 시 worker 무한 block 차단.
 * TimeoutException 발생 시 일반 publish 실패와 동일하게 attempt_count++ 처리.
 *
 * ### Repository wiring (FIX C-3 + hotfix 2026-05-20)
 * persistence-shared 모듈 합류(ADR 0088) 이후 OrderEventOutbox·NotificationOutbox 어댑터는 항상 available
 * 보장 → direct inject.
 *
 * **KillSwitchTogglePort hotfix (2026-05-20)**: KillSwitchToggleAdapter는 api 모듈 단독 등록
 * (consumer 모듈 어댑터 0건) → consumer ApplicationContext 에서 direct inject 시 UnsatisfiedDependencyException
 * → 부팅 fail → 자본 path 전체 차단 (사용자 dogfooding evidence: manual 주문 발행했으나 consumer 처리 0).
 * 따라서 KillSwitchTogglePort 는 `ObjectProvider` 패턴 복원 — graceful skip 으로 부팅 안전성 우선.
 * 후속 의제: KillSwitchToggleAdapter persistence-shared 이관 검토 (ADR 0088 amend).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
@Component
class OrderOutboxPublisherWorker(
    private val outboxRepository: OrderEventOutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val notificationOutboxRepository: NotificationOutboxRepository,
    // hotfix: api 단독 어댑터라 consumer direct inject 불가 → ObjectProvider 복원 (부팅 안전)
    private val killSwitchTogglePortProvider: ObjectProvider<KillSwitchTogglePort>,
) {
    private val log = LoggerFactory.getLogger(OrderOutboxPublisherWorker::class.java)

    companion object {
        const val MAX_ATTEMPT = 3
        const val BATCH_SIZE = 100
        const val FIXED_DELAY_MS = 5_000L
        const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
        const val KILLSWITCH_REASON = "ORDER_OUTBOX_DRAIN_FAILED"
    }

    /**
     * FIX C-2: `@Transactional` 적용 — findPending(FOR UPDATE SKIP LOCKED) 부터 markPublished 까지
     * 단일 트랜잭션 묶음. 다중 worker instance 동시 실행 시 같은 row 중복 처리 차단.
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    @Transactional
    fun drainPending() {
        val pending = outboxRepository.findPending(limit = BATCH_SIZE)
        if (pending.isEmpty()) {
            log.trace("drain worker: PENDING 0건 — skip")
            return
        }

        log.info("drain worker: PENDING {}건 재시도 시작", pending.size)
        var publishedCount = 0
        var failedCount = 0
        var retriedCount = 0

        for (outbox in pending) {
            val id = outbox.id
            if (id == null) {
                log.warn("drain worker: outbox row id null skip — eventId={} topic={}", outbox.eventId, outbox.topic)
                continue
            }
            try {
                val key = outbox.partitionKey ?: outbox.eventId.toString()
                kafkaTemplate
                    .send(outbox.topic, key, outbox.payloadJson)
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                outboxRepository.markPublished(id)
                publishedCount++
                log.info(
                    "drain 성공: outboxId={} topic={} attemptCount={}",
                    id,
                    outbox.topic,
                    outbox.attemptCount,
                )
            } catch (e: TimeoutException) {
                // FIX C-1: broker 응답 지연 — 일반 publish 실패와 동일 분기 처리
                val newCount = handlePublishFailure(outbox, id, "TIMEOUT_${KAFKA_SEND_TIMEOUT_SECONDS}s")
                if (newCount >= MAX_ATTEMPT) failedCount++ else retriedCount++
            } catch (e: Exception) {
                val errMsg = e.message?.take(200) ?: "unknown"
                val newCount = handlePublishFailure(outbox, id, errMsg)
                if (newCount >= MAX_ATTEMPT) failedCount++ else retriedCount++
            }
        }

        log.info(
            "drain worker 완료: pending={} published={} retried={} failed={}",
            pending.size,
            publishedCount,
            retriedCount,
            failedCount,
        )
    }

    /**
     * publish 실패 공통 분기 — TimeoutException + 일반 Exception 양쪽 caller 가 호출.
     * 사이드 이펙트: outbox 상태 갱신 + (MAX 도달 시) 알림 INSERT + KillSwitch engage.
     * 반환: 갱신된 attempt count.
     */
    private fun handlePublishFailure(
        outbox: OrderEventOutbox,
        id: Long,
        errMsg: String,
    ): Int {
        val newAttempt = outbox.attemptCount + 1
        if (newAttempt >= MAX_ATTEMPT) {
            outboxRepository.markFailed(id, "attempt=$newAttempt err=$errMsg")
            log.error(
                "drain 소진 FAILED: outboxId={} topic={} attempt={} err={} — P0 알림 + KillSwitch engage",
                id,
                outbox.topic,
                newAttempt,
                errMsg,
            )
            raiseFailureAlertSafely(outbox, errMsg)
            engageKillSwitchSafely(outbox)
        } else {
            outboxRepository.recordAttemptFailure(id, "attempt=$newAttempt err=$errMsg")
            log.warn(
                "drain 실패 {}/{}: outboxId={} topic={} err={}",
                newAttempt,
                MAX_ATTEMPT,
                id,
                outbox.topic,
                errMsg,
            )
        }
        return newAttempt
    }

    // --- private helper ---

    /**
     * FAILED outbox 발생 시 P0 알림 발사 — NotificationOutbox INSERT (ADR 0044 + ADR 0070).
     *
     * 본 worker는 STOMP/Slack 직접 호출 0 — outbox 패턴 정합 (NotificationOutboxRetryWorker가 발송 처리).
     */
    private fun raiseFailureAlertSafely(
        outbox: OrderEventOutbox,
        errMsg: String,
    ) {
        runCatching {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "alertType" to "ORDER_OUTBOX_DRAIN_FAILED",
                        "outboxId" to outbox.id,
                        "eventType" to outbox.eventType.name,
                        "topic" to outbox.topic,
                        "partitionKey" to outbox.partitionKey,
                        "attemptCount" to outbox.attemptCount + 1,
                        "lastError" to errMsg,
                        "createdAt" to outbox.createdAt.epochSecond,
                    ),
                )
            notificationOutboxRepository.save(
                NotificationOutbox(
                    eventId = UUID.randomUUID(),
                    channel = NotificationChannel.SLACK,
                    priority = NotificationPriority.P0,
                    payloadJson = payload,
                    status = NotificationStatus.PENDING,
                ),
            )
            // payload.alertType="ORDER_OUTBOX_DRAIN_FAILED" 가 운영 식별 키.
            // NotificationEventType enum과는 별도 — alertType은 payload schema, eventType은 DB 컬럼 schema.
            log.info(
                "drain failure 알림 INSERT 완료: outboxId={} alertType=ORDER_OUTBOX_DRAIN_FAILED priority=P0",
                outbox.id,
            )
        }.onFailure { e ->
            log.error(
                "drain failure 알림 INSERT 실패: outboxId={} err={}",
                outbox.id,
                e.message,
            )
        }
    }

    /**
     * 자본 path 보호 — KillSwitch auto-ON (ADR 0043 정합).
     *
     * partitionKey == userId 컨벤션이므로 그 값을 그대로 engage. partitionKey null 시 skip.
     */
    private fun engageKillSwitchSafely(outbox: OrderEventOutbox) {
        val userId = outbox.partitionKey
        if (userId.isNullOrBlank()) {
            log.warn(
                "drain FAILED outbox에 partitionKey(userId) 없음 — KillSwitch skip outboxId={}",
                outbox.id,
            )
            return
        }
        // hotfix: KillSwitchToggleAdapter 는 api 모듈 단독 → consumer 에서 getIfAvailable() == null 가능 (graceful skip)
        val port = killSwitchTogglePortProvider.getIfAvailable()
        if (port == null) {
            log.warn(
                "KillSwitchTogglePort 미등록 (api 모듈 단독 어댑터) — drain FAILED 후속 engage skip outboxId={} userId={}",
                outbox.id,
                userId,
            )
            return
        }
        runCatching { port.engage(userId, KILLSWITCH_REASON) }
            .onSuccess {
                log.error(
                    "KillSwitch auto-ON (drain FAILED): outboxId={} userId={} reason={}",
                    outbox.id,
                    userId,
                    KILLSWITCH_REASON,
                )
            }.onFailure { e ->
                log.error(
                    "KillSwitch engage 실패 — 자본 사고 노출 risk: outboxId={} userId={} err={}",
                    outbox.id,
                    userId,
                    e.message,
                )
            }
    }
}
