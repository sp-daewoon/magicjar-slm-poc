package kr.spdaewoon.magicjar.api.persistence.screener

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal

/**
 * screening_match JPA 엔티티 (ADR 0071 §3 D-2 + Flyway V28).
 *
 * screening_run FK — ON DELETE CASCADE.
 * 옵션 영속 (MAGICJAR_SCREENER_PERSIST=true 환경에서만 INSERT).
 *
 * 헌법 §6: no-arg constructor 필요 → kotlin-jpa 플러그인 의존.
 * body 가진 fun 0건 LOCK.
 */
@Entity
@Table(name = "screening_match")
class ScreeningMatchJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screening_run_id", nullable = false)
    val screeningRun: ScreeningRunJpaEntity,
    @Column(name = "instrument_code", length = 16, nullable = false)
    val instrumentCode: String,
    @Column(name = "rank", nullable = false)
    val rank: Int,
    @Column(name = "score", precision = 10, scale = 6, nullable = false)
    val score: BigDecimal,
    /** {"signal": 0.45, "volume": 0.20, "atr": 0.18, "cap": 0.08} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_components", columnDefinition = "jsonb", nullable = false)
    val scoreComponents: Map<String, Any> = emptyMap(),
    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,
)
