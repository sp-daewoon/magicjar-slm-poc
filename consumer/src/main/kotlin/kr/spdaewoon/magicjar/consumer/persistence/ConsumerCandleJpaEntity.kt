package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant

/**
 * candle JPA 엔티티 — consumer 모듈 자체 사본 (api 모듈과 코드 중복 OK — 모듈 분리 정합).
 * UQ(code, interval, ts) — ON CONFLICT DO NOTHING idempotent INSERT.
 */
@Entity
@Table(
    name = "candle",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_candle_code_interval_ts", columnNames = ["code", "interval", "ts"]),
    ],
)
class ConsumerCandleJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "code", length = 12, nullable = false)
    val code: String,
    @Column(name = "interval", length = 8, nullable = false)
    val interval: String,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "open_raw", precision = 20, scale = 4)
    val openRaw: BigDecimal? = null,
    @Column(name = "high_raw", precision = 20, scale = 4)
    val highRaw: BigDecimal? = null,
    @Column(name = "low_raw", precision = 20, scale = 4)
    val lowRaw: BigDecimal? = null,
    @Column(name = "close_raw", precision = 20, scale = 4)
    val closeRaw: BigDecimal? = null,
    @Column(name = "open_adj", precision = 20, scale = 4)
    val openAdj: BigDecimal? = null,
    @Column(name = "high_adj", precision = 20, scale = 4)
    val highAdj: BigDecimal? = null,
    @Column(name = "low_adj", precision = 20, scale = 4)
    val lowAdj: BigDecimal? = null,
    @Column(name = "close_adj", precision = 20, scale = 4)
    val closeAdj: BigDecimal? = null,
    @Column(name = "volume")
    val volume: Long? = null,
    @Column(name = "source", length = 20, nullable = false)
    val source: String,
) {
    protected constructor() : this(code = "", interval = "", ts = Instant.EPOCH, source = "")
}
