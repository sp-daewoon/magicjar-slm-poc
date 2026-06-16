package kr.spdaewoon.magicjar.persistence.shared.notification.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * notification_outbox Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [NotificationOutboxRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
interface NotificationOutboxJpaRepository : JpaRepository<NotificationOutboxJpaEntity, Long> {
    /** PENDING 상태 아웃박스 목록 — created_at ASC (오래된 것 먼저 재시도) */
    fun findByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: Pageable,
    ): List<NotificationOutboxJpaEntity>

    /**
     * 가장 오래된 PENDING row의 created_at (Outbox drain lag Gauge 측정 source — ADR 0094 P0-OPS-6 + ADR 0095 §2-1 S 보조).
     *
     * 결과 epoch seconds 반환. PENDING 0건 시 null.
     * 0건 시 `PersistenceMetrics.updateOutboxDrainLag` caller가 0 갱신 의무.
     */
    @Query(
        value = "SELECT EXTRACT(EPOCH FROM MIN(created_at))::BIGINT FROM notification_outbox WHERE status = 'PENDING'",
        nativeQuery = true,
    )
    fun findOldestPendingCreatedAtEpochSeconds(): Long?
}
