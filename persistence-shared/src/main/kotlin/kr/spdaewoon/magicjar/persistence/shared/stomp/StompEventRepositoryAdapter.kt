package kr.spdaewoon.magicjar.persistence.shared.stomp

import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import kr.spdaewoon.magicjar.domain.stomp.port.UnreadBounds
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * domain [StompEventRepository] port JPA 구현 어댑터 — Flyway V41 정합 (ADR 0084 P1-2).
 *
 * 헌법 §6 (ADR 0051): helper 메서드는 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Component
class StompEventRepositoryAdapter(
    private val jpaRepository: StompEventJpaRepository,
) : StompEventRepository {
    private val log = LoggerFactory.getLogger(StompEventRepositoryAdapter::class.java)

    @Transactional
    override fun save(event: StompEvent): StompEvent {
        val entity = toEntity(event)
        val saved = jpaRepository.save(entity)
        log.debug(
            "stomp_event save: id={} userId={} channel={} eventType={}",
            saved.id,
            saved.userId,
            saved.channel,
            saved.eventType,
        )
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findSince(
        userId: Long,
        since: Instant,
        channel: String?,
        limit: Int,
    ): List<StompEvent> {
        val pageable = PageRequest.of(0, limit.coerceIn(1, 500))
        val rows =
            if (channel.isNullOrBlank()) {
                jpaRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    userId,
                    since,
                    pageable,
                )
            } else {
                jpaRepository.findByUserIdAndChannelAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    userId,
                    channel,
                    since,
                    pageable,
                )
            }
        return rows.map { toDomain(it) }
    }

    @Transactional(readOnly = true)
    override fun countUnreadSince(
        userId: Long,
        since: Instant,
        channel: String?,
    ): Long =
        if (channel.isNullOrBlank()) {
            jpaRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, since)
        } else {
            jpaRepository.countByUserIdAndChannelAndCreatedAtGreaterThanEqual(userId, channel, since)
        }

    @Transactional(readOnly = true)
    override fun findUnreadBoundsSince(
        userId: Long,
        since: Instant,
        channel: String?,
    ): UnreadBounds? {
        val (minTs, maxTs) =
            if (channel.isNullOrBlank()) {
                jpaRepository.findMinCreatedAtSince(userId, since) to
                    jpaRepository.findMaxCreatedAtSince(userId, since)
            } else {
                jpaRepository.findMinCreatedAtSinceWithChannel(userId, channel, since) to
                    jpaRepository.findMaxCreatedAtSinceWithChannel(userId, channel, since)
            }
        if (minTs == null || maxTs == null) return null
        return UnreadBounds(firstTs = minTs, lastTs = maxTs)
    }

    @Transactional
    override fun deleteOlderThan(cutoff: Instant): Int {
        val deleted = jpaRepository.deleteOlderThan(cutoff)
        if (deleted > 0) {
            log.info("stomp_event retention: deleted={} cutoff={}", deleted, cutoff)
        }
        return deleted
    }

    // --- private helper ---

    private fun toEntity(event: StompEvent): StompEventJpaEntity =
        StompEventJpaEntity(
            id = event.id,
            userId = event.userId,
            channel = event.channel,
            eventType = event.eventType,
            payloadJson = event.payloadJson,
            createdAt = event.createdAt,
        )

    private fun toDomain(entity: StompEventJpaEntity): StompEvent =
        StompEvent(
            id = entity.id,
            userId = entity.userId,
            channel = entity.channel,
            eventType = entity.eventType,
            payloadJson = entity.payloadJson,
            createdAt = entity.createdAt,
        )
}
