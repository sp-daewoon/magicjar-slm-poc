package kr.spdaewoon.magicjar.api.persistence.scenario

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "scenario_position")
class ScenarioPositionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    val run: ScenarioRunJpaEntity,
    @Column(name = "code", nullable = false, length = 8)
    val code: String,
    @Column(name = "quantity", nullable = false)
    val quantity: Long,
    @Column(name = "average_price", nullable = false, precision = 19, scale = 4)
    val averagePrice: BigDecimal,
    @Column(name = "virtual_time", nullable = false)
    val virtualTime: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
