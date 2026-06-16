package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * corp_action JPA 엔티티 (batch 모듈 — T-CA1 PR-29b).
 *
 * corp_action 테이블 (Flyway V7 정합).
 * UNIQUE INDEX: instrument_code + type + ex_date + source (upsert 기준).
 *
 * ADR 0018 §2-1 정합.
 * ADR 0051 §2 — JpaRepository body fun 금지 → CorpActionJpaRepository는 @Query 전용.
 */
@Entity
@Table(name = "corp_action")
class CorpActionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "instrument_code", length = 8, nullable = false)
    val instrumentCode: String,
    @Column(name = "type", length = 32, nullable = false)
    val type: String,
    @Column(name = "ex_date", nullable = false)
    val exDate: LocalDate,
    @Column(name = "ratio", precision = 15, scale = 8)
    val ratio: BigDecimal? = null,
    @Column(name = "cash_dividend", precision = 15, scale = 4)
    val cashDividend: BigDecimal? = null,
    @Column(name = "merge_ratio", precision = 15, scale = 8)
    val mergeRatio: BigDecimal? = null,
    @Column(name = "source", length = 32, nullable = false)
    val source: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    val rawPayload: String? = null,
    @Column(name = "applied_at")
    val appliedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        instrumentCode = "",
        type = "",
        exDate = LocalDate.EPOCH,
        source = "",
    )
}
