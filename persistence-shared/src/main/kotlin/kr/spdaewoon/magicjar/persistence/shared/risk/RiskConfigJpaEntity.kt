package kr.spdaewoon.magicjar.persistence.shared.risk

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * risk_config JPA 엔티티 — Flyway V16 정합 (ADR 0067 §5-1 H-1).
 *
 * 2026-06-10 incident fix: api 모듈 → persistence-shared 이동 (ADR 0088 패턴).
 * consumer가 RiskConfigRepository 빈을 발견하지 못해 자동매매 경로가 항상
 * defaultFor(1M/10%)를 평가하던 silent 배선 결함 정정 — api·consumer 공유.
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(persistence-shared 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 * UQ user_id — 1 row per user.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Entity
@Table(name = "risk_config")
class RiskConfigJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, unique = true, length = 40)
    val userId: String,
    @Column(name = "max_position_abs", precision = 19, scale = 4)
    val maxPositionAbs: BigDecimal? = null,
    @Column(name = "max_position_pct", precision = 7, scale = 4)
    val maxPositionPct: BigDecimal? = null,
    @Column(name = "dll_abs", precision = 19, scale = 4)
    val dllAbs: BigDecimal? = null,
    @Column(name = "dll_pct", precision = 7, scale = 4)
    val dllPct: BigDecimal? = null,
    @Column(name = "max_concurrent_positions", nullable = false)
    val maxConcurrentPositions: Int = 5,
    @Column(name = "trade_freq_per_min", nullable = false)
    val tradeFreqPerMin: Int = 3,
    @Column(name = "trade_freq_per_day", nullable = false)
    val tradeFreqPerDay: Int = 50,
    @Column(name = "auto_pause_threshold", nullable = false)
    val autoPauseThreshold: Int = 10,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
