package kr.spdaewoon.magicjar.api.persistence.risk

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * risk_event JPA 엔티티 — Flyway V17 정합 (ADR 0067 §5-2 G-1).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 * signal_id FK — ON DELETE CASCADE (Flyway V17 정합).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Entity
@Table(name = "risk_event")
class RiskEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "signal_id")
    val signalId: Long? = null,
    @Column(name = "rule", nullable = false, length = 40)
    val rule: String,
    @Column(name = "verdict", nullable = false, length = 20)
    val verdict: String,
    @Column(name = "reason", length = 500)
    val reason: String? = null,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
