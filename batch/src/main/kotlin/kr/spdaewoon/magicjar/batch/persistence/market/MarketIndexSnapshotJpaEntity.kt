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
 * market_index_snapshot JPA entity (Flyway V47 LOCK · ADR 0100 §2-7 정합).
 *
 * UNIQUE INDEX: (code, ts) — upsert 멱등 키.
 *
 * ADR 0051 §2 정합 — Repository body fun 0 (헌법 §6).
 */
@Entity
@Table(name = "market_index_snapshot")
class MarketIndexSnapshotJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "code", length = 16, nullable = false)
    val code: String,
    @Column(name = "market", length = 16, nullable = false)
    val market: String,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "price", precision = 15, scale = 4, nullable = false)
    val price: BigDecimal,
    @Column(name = "open", precision = 15, scale = 4)
    val open: BigDecimal? = null,
    @Column(name = "high", precision = 15, scale = 4)
    val high: BigDecimal? = null,
    @Column(name = "low", precision = 15, scale = 4)
    val low: BigDecimal? = null,
    @Column(name = "close", precision = 15, scale = 4)
    val close: BigDecimal? = null,
    @Column(name = "change_rate", precision = 8, scale = 4)
    val changeRate: BigDecimal? = null,
    @Column(name = "volume")
    val volume: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    val rawPayload: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    /** Hibernate 빈 생성자 (proxy 생성용) */
    protected constructor() : this(
        code = "",
        market = "",
        ts = Instant.EPOCH,
        price = BigDecimal.ZERO,
    )
}
