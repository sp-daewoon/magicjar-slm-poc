package kr.spdaewoon.magicjar.persistence.shared.notification.outbox

import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * domain [NotificationOutboxRepository] port JPA 구현 어댑터 — Flyway V27 정합 (ADR 0044 + ADR 0070 §3-3).
 *
 * 헌법 §6 (ADR 0051): helper 메서드는 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class NotificationOutboxRepositoryAdapter(
    private val jpaRepository: NotificationOutboxJpaRepository,
    // ADR 0094 P0-OPS-6 — Outbox drain lag Gauge wiring (ObjectProvider graceful)
    private val persistenceMetricsProvider: ObjectProvider<PersistenceMetrics>,
) : NotificationOutboxRepository {
    private val log = LoggerFactory.getLogger(NotificationOutboxRepositoryAdapter::class.java)

    @Transactional
    override fun save(outbox: NotificationOutbox): NotificationOutbox {
        val entity = toEntity(outbox)
        val saved = jpaRepository.save(entity)
        log.debug(
            "notification_outbox save: id={} eventId={} status={}",
            saved.id,
            saved.eventId,
            saved.status,
        )
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findPending(limit: Int): List<NotificationOutbox> {
        val pageable = PageRequest.of(0, limit)
        val result =
            jpaRepository
                .findByStatusOrderByCreatedAtAsc(NotificationStatus.PENDING.name, pageable)
                .map { toDomain(it) }
        // ADR 0094 P0-OPS-6 — drain lag Gauge 갱신
        updateDrainLagSafely()
        return result
    }

    /** drain lag 갱신 — adapter scope private helper (헌법 §6 ADR 0051 정합). */
    private fun updateDrainLagSafely() {
        runCatching {
            val oldest = jpaRepository.findOldestPendingCreatedAtEpochSeconds()
            val lag =
                if (oldest == null) {
                    0L
                } else {
                    (Instant.now().epochSecond - oldest).coerceAtLeast(0)
                }
            persistenceMetricsProvider.getIfAvailable()?.updateOutboxDrainLag(
                PersistenceMetrics.TAG_OUTBOX_NOTIFICATION,
                lag,
            )
        }.onFailure { e ->
            log.trace("NotificationOutbox drain lag 갱신 skip: err={}", e.message)
        }
    }

    @Transactional(readOnly = true)
    override fun findByStatus(status: NotificationStatus): List<NotificationOutbox> {
        val pageable = PageRequest.of(0, 1000)
        return jpaRepository
            .findByStatusOrderByCreatedAtAsc(status.name, pageable)
            .map { toDomain(it) }
    }

    @Transactional
    override fun markSent(id: Long): NotificationOutbox? {
        val entity = jpaRepository.findById(id).orElse(null) ?: return null
        entity.status = NotificationStatus.SENT.name
        entity.sentAt = Instant.now()
        val saved = jpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional
    override fun markFailed(
        id: Long,
        error: String,
    ): NotificationOutbox? {
        val entity = jpaRepository.findById(id).orElse(null) ?: return null
        entity.status = NotificationStatus.FAILED.name
        entity.lastError = error.take(500) // TEXT column 과부하 방지
        entity.retryCount++
        val saved = jpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional
    override fun incrementRetryCount(id: Long): NotificationOutbox? {
        val entity = jpaRepository.findById(id).orElse(null) ?: return null
        entity.retryCount++
        entity.lastError = "retry_count=${entity.retryCount}"
        val saved = jpaRepository.save(entity)
        return toDomain(saved)
    }

    // --- private helper ---

    private fun toEntity(outbox: NotificationOutbox): NotificationOutboxJpaEntity =
        NotificationOutboxJpaEntity(
            id = outbox.id,
            eventId = outbox.eventId,
            channel = outbox.channel.name,
            priority = outbox.priority.name,
            payload = outbox.payloadJson,
            status = outbox.status.name,
            createdAt = outbox.createdAt,
            sentAt = outbox.sentAt,
            retryCount = outbox.retryCount,
            lastError = outbox.lastError,
        )

    private fun toDomain(entity: NotificationOutboxJpaEntity): NotificationOutbox =
        NotificationOutbox(
            id = entity.id,
            eventId = entity.eventId,
            channel = safeChannel(entity.channel),
            priority = safePriority(entity.priority),
            payloadJson = entity.payload,
            status = safeStatus(entity.status),
            retryCount = entity.retryCount,
            lastError = entity.lastError,
            createdAt = entity.createdAt,
            sentAt = entity.sentAt,
        )

    private fun safeChannel(value: String): NotificationChannel =
        runCatching { NotificationChannel.valueOf(value) }.getOrDefault(NotificationChannel.SLACK)

    private fun safePriority(value: String): NotificationPriority =
        runCatching { NotificationPriority.valueOf(value) }.getOrDefault(NotificationPriority.P2)

    private fun safeStatus(value: String): NotificationStatus =
        runCatching { NotificationStatus.valueOf(value) }.getOrDefault(NotificationStatus.PENDING)
}
