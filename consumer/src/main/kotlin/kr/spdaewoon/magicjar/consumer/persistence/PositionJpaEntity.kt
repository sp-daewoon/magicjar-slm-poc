package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * position JPA 엔티티 (consumer 모듈 — ADR 0042 Bootstrap STAGE 0-2).
 * UQ(user_id, code) — upsert는 native SQL ON CONFLICT 사용.
 */
@Entity
@Table(name = "position")
class PositionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String,
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "quantity", nullable = false)
    val quantity: Int = 0,
    @Column(name = "avg_price", precision = 19, scale = 4, nullable = false)
    val avgPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "source", length = 10, nullable = false)
    val source: String = "MOCK",
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(userId = "", code = "")
}
