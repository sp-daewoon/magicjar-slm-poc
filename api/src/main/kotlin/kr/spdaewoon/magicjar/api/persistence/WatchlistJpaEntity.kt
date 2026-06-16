package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * watchlist JPA 엔티티 (ADR 0028·0040).
 * UQ(user_id, code) — 중복 등록 시 DataIntegrityViolationException → WatchlistController에서 409 매핑.
 */
@Entity
@Table(name = "watchlist")
class WatchlistJpaEntity(
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
