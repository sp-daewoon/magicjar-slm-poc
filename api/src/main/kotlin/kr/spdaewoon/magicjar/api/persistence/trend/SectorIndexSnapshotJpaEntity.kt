package kr.spdaewoon.magicjar.api.persistence.trend

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

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
) {
    protected constructor() : this(
        sectorCode = "",
        sectorName = "",
        ts = Instant.EPOCH,
        price = BigDecimal.ZERO,
        changeRate = BigDecimal.ZERO,
    )
}
