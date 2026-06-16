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
    @Column(name = "change_rate", precision = 8, scale = 4)
    val changeRate: BigDecimal? = null,
) {
    protected constructor() : this(
        code = "",
        market = "",
        ts = Instant.EPOCH,
        price = BigDecimal.ZERO,
    )
}
