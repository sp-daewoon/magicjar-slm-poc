package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * watchlist JPA 엔티티 — consumer 모듈 사본 (api 모듈과 코드 중복 OK — 모듈 분리 정합).
 * SubscriptionRouter 부팅 reconcile 시 조회용.
 */
@Entity
@Table(name = "watchlist")
class ConsumerWatchlistJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String,
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "priority", nullable = false)
    val priority: Int = 0,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(userId = "", code = "")
}
