package kr.spdaewoon.magicjar.persistence.shared.event

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * system_event Spring Data JPA 리포지터리 — Flyway V44 정합.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 * helper 는 caller adapter (예: P2 SystemEventQueryService) private fun 위치.
 *
 * ### 쿼리 매트릭스 (P2.1 — final reviewer ★★ #2 fix)
 *
 * | userId | unreadOnly | items finder                                              | totalCount finder                  | unreadCount finder                       |
 * |--------|------------|-----------------------------------------------------------|------------------------------------|------------------------------------------|
 * | Y      | true       | findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc        | countByUserIdAndViewedAtIsNull     | countByUserIdAndViewedAtIsNull           |
 * | Y      | false      | findByUserIdOrderByOccurredAtDesc                         | countByUserId                      | countByUserIdAndViewedAtIsNull           |
 * | N      | true       | findByViewedAtIsNullOrderByOccurredAtDesc                 | countByViewedAtIsNull              | countByViewedAtIsNull                    |
 * | N      | false      | findAllByOrderByOccurredAtDesc                            | count() (JpaRepository default)    | countByViewedAtIsNull                    |
 *
 * count*는 efficient COUNT(*) — 기존 패턴(findBy* + .size) 의 풀로딩 회피.
 *
 * ### finder 개요
 * - [findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc] : 사용자별 미확인 (UI 알림센터 — index idx_system_event_user_unread)
 * - [findByUserIdOrderByOccurredAtDesc]                  : 사용자별 전체 페이지네이션 (P2.1 신규)
 * - [findByViewedAtIsNullOrderByOccurredAtDesc]          : 전체 미확인 — userId null + unreadOnly (P2.1 신규)
 * - [findAllByOrderByOccurredAtDesc]                     : 운영 dashboard 페이지네이션 전체 (P2 admin)
 * - [findByCorrelationIdOrderByOccurredAtAsc]            : correlation_id 그룹 표시 (WS disconnect→reconnect 묶음 — P2)
 * - [countByUserId]                                      : 사용자별 row 수 (P2.1 신규)
 * - [countByUserIdAndViewedAtIsNull]                     : 사용자별 unread 수 (P2.1 신규)
 * - [countByViewedAtIsNull]                              : 전체 unread 수 (P2.1 신규)
 * - [deleteOlderThan]                                    : 운영 대시보드 7일 보존 retention cron 대상
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
interface SystemEventJpaRepository : JpaRepository<SystemEventJpaEntity, Long> {
    fun findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(
        userId: String,
        pageable: Pageable,
    ): List<SystemEventJpaEntity>

    fun findByUserIdOrderByOccurredAtDesc(
        userId: String,
        pageable: Pageable,
    ): List<SystemEventJpaEntity>

    fun findByViewedAtIsNullOrderByOccurredAtDesc(pageable: Pageable): List<SystemEventJpaEntity>

    fun findAllByOrderByOccurredAtDesc(pageable: Pageable): List<SystemEventJpaEntity>

    fun findByCorrelationIdOrderByOccurredAtAsc(correlationId: String): List<SystemEventJpaEntity>

    fun countByUserId(userId: String): Long

    fun countByUserIdAndViewedAtIsNull(userId: String): Long

    fun countByViewedAtIsNull(): Long

    @Modifying
    @Query("DELETE FROM SystemEventJpaEntity e WHERE e.occurredAt < :cutoff")
    fun deleteOlderThan(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
