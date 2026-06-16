package kr.spdaewoon.magicjar.persistence.shared.order.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * order_event_outbox Spring Data JPA 리포지터리 (ADR 0084 P1-1 + Flyway V40).
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * helper / branching 은 [OrderEventOutboxRepositoryAdapter] private fun 위치.
 *
 * ### 다중 worker race condition 차단 (FIX C-2)
 * [findPendingForUpdate] 는 PostgreSQL native `FOR UPDATE SKIP LOCKED` 사용 — 다중 JVM·다중 worker instance
 * 동시 실행 시 같은 row 중복 select 차단. 락 미획득 row 는 skip 하므로 worker 간 작업 자연 분배.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
interface OrderEventOutboxJpaRepository : JpaRepository<OrderEventOutboxJpaEntity, Long> {
    /** PENDING worker poll — published_at IS NULL · 오래된 순 (legacy — race condition risk, 신규 호출은 findPendingForUpdate 사용). */
    fun findByPublishedAtIsNullOrderByCreatedAtAsc(pageable: Pageable): List<OrderEventOutboxJpaEntity>

    /**
     * PENDING worker poll — `FOR UPDATE SKIP LOCKED` (PostgreSQL native).
     *
     * 다중 worker instance 동시 실행 시 같은 row 중복 처리 차단. 호출자 @Transactional (write) 의무.
     * 트랜잭션 종료까지 락 유지 → markPublished/recordAttemptFailure 동일 트랜잭션 안에서 처리 의무.
     */
    @Query(
        value = """
            SELECT * FROM order_event_outbox
            WHERE published_at IS NULL
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findPendingForUpdate(
        @Param("limit") limit: Int,
    ): List<OrderEventOutboxJpaEntity>

    /** 상태별 운영 조회 — created_at ASC */
    fun findByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: Pageable,
    ): List<OrderEventOutboxJpaEntity>

    /**
     * 가장 오래된 PENDING row의 created_at (Outbox drain lag Gauge 측정 source — ADR 0095 §2-1 S 보조).
     *
     * 결과 epoch seconds 반환 (timezone 무관). PENDING 0건 시 null.
     * 0건 시 `PersistenceMetrics.updateOutboxDrainLag` caller가 0 갱신 의무 (gauge 잔존 stale 값 방지).
     */
    @Query(
        value = "SELECT EXTRACT(EPOCH FROM MIN(created_at))::BIGINT FROM order_event_outbox WHERE published_at IS NULL",
        nativeQuery = true,
    )
    fun findOldestPendingCreatedAtEpochSeconds(): Long?
}
