package kr.spdaewoon.magicjar.batch.persistence.market

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

/**
 * sector_index_snapshot JPA entity (Flyway V48 LOCK · ADR 0100 §2-7 정합).
 *
 * UNIQUE INDEX: (sector_code, ts) — upsert 멱등 키.
 *
 * ADR 0051 §2 정합 — Repository body fun 0 (헌법 §6).
 */
@Entity
@Table(name = "sector_index_snapshot")
class SectorIndexSnapshotJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "sector_code", length = 16, nullable = false)
    val sectorCode: String,
    @Column(name = "sector_name", length = 64, nullable = false)
    val sectorName: String,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "price", precision = 15, scale = 4, nullable = false)
    val price: BigDecimal,
    @Column(name = "change_rate", precision = 8, scale = 4, nullable = false)
    val changeRate: BigDecimal,
    @Column(name = "trade_value", precision = 20, scale = 2)
    val tradeValue: BigDecimal? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    val rawPayload: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        sectorCode = "",
        sectorName = "",
        ts = Instant.EPOCH,
        price = BigDecimal.ZERO,
        changeRate = BigDecimal.ZERO,
    )
}
