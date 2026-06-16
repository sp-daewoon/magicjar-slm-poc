package kr.spdaewoon.magicjar.api.persistence.position

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * position JPA 엔티티 — Flyway V18 정합 (ADR 0067 §5-4 G-1 + ADR 0042 정합).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 * qty=0이면 포지션 청산 (DB 행 유지 — ADR 0042 Position.isClosed 정합).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Entity
@Table(name = "position")
class PositionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "code", nullable = false, length = 8)
    val code: String,
    @Column(name = "qty", nullable = false)
    val qty: Long,
    @Column(name = "avg_price", nullable = false, precision = 19, scale = 4)
    val avgPrice: BigDecimal,
    @Column(name = "entry_ts", nullable = false)
    val entryTs: Instant,
    @Column(name = "source", nullable = false, length = 10)
    val source: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
