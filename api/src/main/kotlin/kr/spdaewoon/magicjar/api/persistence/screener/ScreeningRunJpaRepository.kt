package kr.spdaewoon.magicjar.api.persistence.screener

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * screening_run Spring Data JPA Repository (ADR 0071 §3 D-2).
 *
 * 헌법 §6 LOCK: interface 내부 body 가진 default fun 0건.
 * abstract fun(@Query 포함) 단독 허용.
 * caller 클래스(ScreeningRunRepositoryAdapter)에서 private helper 패턴 사용.
 */
interface ScreeningRunJpaRepository : JpaRepository<ScreeningRunJpaEntity, Long> {
    /** 사용자별 screening 실행 이력 (최신순 페이징). */
    fun findByUserIdOrderByStartedAtDesc(
        userId: Long,
        pageable: Pageable,
    ): Page<ScreeningRunJpaEntity>

    /** screening_run + screening_match 배열 JOIN FETCH (N+1 회피). */
    @Query(
        """
        SELECT DISTINCT r FROM ScreeningRunJpaEntity r
        LEFT JOIN FETCH r.matches m
        WHERE r.id = :id AND r.userId = :userId
        """,
    )
    fun findByIdAndUserId(
        @Param("id") id: Long,
        @Param("userId") userId: Long,
    ): ScreeningRunJpaEntity?
}
