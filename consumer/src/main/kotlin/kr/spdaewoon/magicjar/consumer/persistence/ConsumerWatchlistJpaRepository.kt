package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * watchlist Spring Data JPA Repository — consumer 모듈 (read-only 조회용).
 */
interface ConsumerWatchlistJpaRepository : JpaRepository<ConsumerWatchlistJpaEntity, Long> {
    fun findByUserIdOrderByPriorityAscCreatedAtDesc(userId: String): List<ConsumerWatchlistJpaEntity>

    fun findAllByOrderByPriorityAscCreatedAtDesc(): List<ConsumerWatchlistJpaEntity>
}
