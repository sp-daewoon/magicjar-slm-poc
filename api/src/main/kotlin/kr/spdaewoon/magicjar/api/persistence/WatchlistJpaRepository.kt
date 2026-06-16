package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * watchlist Spring Data JPA Repository.
 */
interface WatchlistJpaRepository : JpaRepository<WatchlistJpaEntity, Long> {
    /**
     * priority 오름차순 → created_at 내림차순 (ADR 0040 §2-2 정렬 정책).
     */
    fun findByUserIdOrderByPriorityAscCreatedAtDesc(userId: String): List<WatchlistJpaEntity>

    fun existsByUserIdAndCode(
        userId: String,
        code: String,
    ): Boolean

    @Modifying
    @Transactional
    @Query("DELETE FROM WatchlistJpaEntity w WHERE w.userId = :userId AND w.code = :code")
    fun deleteByUserIdAndCode(
        userId: String,
        code: String,
    ): Int
}
