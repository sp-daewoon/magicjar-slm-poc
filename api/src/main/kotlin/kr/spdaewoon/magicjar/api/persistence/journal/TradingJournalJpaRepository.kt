package kr.spdaewoon.magicjar.api.persistence.journal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * trading_journal Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건 LOCK.
 * body 가진 helper는 [TradingJournalRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
interface TradingJournalJpaRepository :
    JpaRepository<TradingJournalJpaEntity, Long>,
    JpaSpecificationExecutor<TradingJournalJpaEntity> {
    /** 사용자별 시계열 페이징 (occurred_at DESC) — 필터 없음 */
    fun findByUserIdOrderByOccurredAtDesc(
        userId: Long,
        pageable: Pageable,
    ): Page<TradingJournalJpaEntity>

    /** 사용자 + 기간 필터 페이징 */
    @Query(
        """
        SELECT e FROM TradingJournalJpaEntity e
        WHERE e.userId = :userId
          AND (:from IS NULL OR e.occurredAt >= :from)
          AND (:to IS NULL OR e.occurredAt <= :to)
        ORDER BY e.occurredAt DESC
        """,
    )
    fun findByUserIdAndRange(
        @Param("userId") userId: Long,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable,
    ): Page<TradingJournalJpaEntity>

    /** 사용자 + 기간 + 이벤트 타입 복수 필터 페이징 */
    @Query(
        """
        SELECT e FROM TradingJournalJpaEntity e
        WHERE e.userId = :userId
          AND (:from IS NULL OR e.occurredAt >= :from)
          AND (:to IS NULL OR e.occurredAt <= :to)
          AND e.eventType IN :eventTypes
        ORDER BY e.occurredAt DESC
        """,
    )
    fun findByUserIdAndRangeAndEventTypes(
        @Param("userId") userId: Long,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("eventTypes") eventTypes: List<String>,
        pageable: Pageable,
    ): Page<TradingJournalJpaEntity>

    /** 사용자 + 기간 + 종목 코드 필터 페이징 */
    @Query(
        """
        SELECT e FROM TradingJournalJpaEntity e
        WHERE e.userId = :userId
          AND (:from IS NULL OR e.occurredAt >= :from)
          AND (:to IS NULL OR e.occurredAt <= :to)
          AND e.instrumentCode = :instrumentCode
        ORDER BY e.occurredAt DESC
        """,
    )
    fun findByUserIdAndRangeAndCode(
        @Param("userId") userId: Long,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("instrumentCode") instrumentCode: String,
        pageable: Pageable,
    ): Page<TradingJournalJpaEntity>

    /** 사용자 + 기간 + 이벤트 타입 + 종목 코드 복합 필터 페이징 */
    @Query(
        """
        SELECT e FROM TradingJournalJpaEntity e
        WHERE e.userId = :userId
          AND (:from IS NULL OR e.occurredAt >= :from)
          AND (:to IS NULL OR e.occurredAt <= :to)
          AND e.eventType IN :eventTypes
          AND e.instrumentCode = :instrumentCode
        ORDER BY e.occurredAt DESC
        """,
    )
    fun findByUserIdAndRangeAndEventTypesAndCode(
        @Param("userId") userId: Long,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("eventTypes") eventTypes: List<String>,
        @Param("instrumentCode") instrumentCode: String,
        pageable: Pageable,
    ): Page<TradingJournalJpaEntity>

    /** UQ 충돌 사전 확인 (멱등성) */
    fun findBySourceTableAndSourceIdAndEventType(
        sourceTable: String,
        sourceId: Long,
        eventType: String,
    ): TradingJournalJpaEntity?

    /** userId + id 격리 조회 */
    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): TradingJournalJpaEntity?
}
