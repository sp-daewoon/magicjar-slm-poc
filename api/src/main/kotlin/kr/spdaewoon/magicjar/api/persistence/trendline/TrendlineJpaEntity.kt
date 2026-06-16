package kr.spdaewoon.magicjar.api.persistence.trendline

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "trendline")
class TrendlineJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String,
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "source_interval", length = 8, nullable = false)
    val sourceInterval: String,
    @Column(name = "line_type", length = 16, nullable = false)
    val lineType: String,
    @Column(name = "source", length = 16, nullable = false)
    val source: String,
    @Column(name = "anchor1_ts", nullable = false)
    val anchor1Ts: Instant,
    @Column(name = "anchor1_price", precision = 19, scale = 4, nullable = false)
    val anchor1Price: BigDecimal,
    @Column(name = "anchor2_ts", nullable = false)
    val anchor2Ts: Instant,
    @Column(name = "anchor2_price", precision = 19, scale = 4, nullable = false)
    val anchor2Price: BigDecimal,
    @Column(name = "extend_right", nullable = false)
    val extendRight: Boolean = true,
    @Column(name = "extend_left", nullable = false)
    val extendLeft: Boolean = false,
    @Column(name = "label", length = 80)
    val label: String? = null,
    @Column(name = "color", length = 16)
    val color: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = "",
        code = "",
        sourceInterval = "",
        lineType = "",
        source = "",
        anchor1Ts = Instant.EPOCH,
        anchor1Price = BigDecimal.ZERO,
        anchor2Ts = Instant.EPOCH.plusSeconds(1),
        anchor2Price = BigDecimal.ZERO,
    )
}
