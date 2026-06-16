package kr.spdaewoon.magicjar.consumer.notification

import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * notification_outbox PENDING 재시도 worker (ADR 0044 + spec §2-4 LOCK).
 *
 * ### 스케줄
 * `@Scheduled(fixedDelay = 5 * 60 * 1000)` — 5분 주기 (spec §2-4 + §11-3 시나리오 정합).
 * fixedDelay 사용: 직전 실행 완료 후 5분 대기 (동시 실행 방지).
 *
 * ### 처리 흐름 (spec §11-3 시나리오 3 LOCK)
 * 1. findPending(limit=50) — PENDING row 조회
 * 2. 각 row → SlackWebhookSender.sendOutbox()
 * 3. 성공: markSent() → status=SENT
 * 4. 실패: incrementRetryCount()
 *    retry_count ≥ MAX_RETRY(5): markFailed() → status=FAILED + log.error
 *
 * ### Repository wiring (FIX C-3)
 * persistence-shared 모듈 합류(ADR 0088) 이후 어댑터 항상 available 보장 → ObjectProvider 폐기 +
 * direct inject. SlackWebhookSender 는 webhook URL 미설정 시 빈 sender Bean 가능하므로 ObjectProvider 유지.
 *
 * ### graceful 조건
 * - SlackWebhookSender 미등록 → skip + log.warn (webhook URL 0 시 옵셔널)
 * - Slack webhook 미설정 → sender 가 null 반환 → PENDING 유지
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class NotificationOutboxRetryWorker(
    private val outboxRepository: NotificationOutboxRepository,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
) {
    private val log = LoggerFactory.getLogger(NotificationOutboxRetryWorker::class.java)

    companion object {
        private const val MAX_RETRY = 5
        private const val BATCH_SIZE = 50
        private const val RECENT_PENDING_GRACE_SECONDS = 30L
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    fun retryPending() {
        val sender = slackSenderProvider.getIfAvailable()
        if (sender == null) {
            log.warn("SlackWebhookSender 미등록 — retry worker skip")
            return
        }

        val cutoff = Instant.now().minusSeconds(RECENT_PENDING_GRACE_SECONDS)
        val pending =
            outboxRepository
                .findPending(limit = BATCH_SIZE)
                .filter { !it.createdAt.isAfter(cutoff) }
        if (pending.isEmpty()) {
            log.debug("retry worker: PENDING 0건 — skip")
            return
        }

        log.info("retry worker: PENDING {}건 재시도 시작", pending.size)
        var sentCount = 0
        var failedCount = 0

        for (outbox in pending) {
            val id = outbox.id
            if (id == null) {
                log.warn("retry worker: notification outbox id null skip eventId={}", outbox.eventId)
                continue
            }
            try {
                val sent = sender.sendOutbox(outbox)
                if (sent) {
                    outboxRepository.markSent(id)
                    sentCount++
                    log.info("retry 성공: outboxId={} retryCount={}", id, outbox.retryCount)
                } else {
                    val newRetryCount = outbox.retryCount + 1
                    if (newRetryCount >= MAX_RETRY) {
                        outboxRepository.markFailed(id, "retry_count=$newRetryCount — Slack 전송 실패 소진")
                        failedCount++
                        log.error(
                            "retry 소진 FAILED: outboxId={} retryCount={} — 수동 확인 필요",
                            id,
                            newRetryCount,
                        )
                    } else {
                        outboxRepository.incrementRetryCount(id)
                        log.warn("retry 실패 {}/{}: outboxId={}", newRetryCount, MAX_RETRY, id)
                    }
                }
            } catch (e: Exception) {
                log.error("retry worker 처리 중 예외: outboxId={} err={}", id, e.message, e)
                val newRetryCount = outbox.retryCount + 1
                if (newRetryCount >= MAX_RETRY) {
                    outboxRepository.markFailed(id, "exception: ${e.message?.take(200)}")
                    failedCount++
                } else {
                    outboxRepository.incrementRetryCount(id)
                }
            }
        }

        log.info(
            "retry worker 완료: PENDING={} SENT={} FAILED={}",
            pending.size,
            sentCount,
            failedCount,
        )
    }
}
