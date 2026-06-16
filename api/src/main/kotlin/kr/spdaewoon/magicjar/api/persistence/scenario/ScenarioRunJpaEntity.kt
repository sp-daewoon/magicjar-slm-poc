package kr.spdaewoon.magicjar.api.persistence.scenario

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

@Entity
@Table(name = "scenario_run")
class ScenarioRunJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "strategy_name", length = 200)
    val strategyName: String? = null,
    @Column(name = "code", nullable = false, length = 8)
    val code: String,
    @Column(name = "interval", nullable = false, length = 8)
    val interval: String,
    @Column(name = "range_from", nullable = false)
    val rangeFrom: Instant,
    @Column(name = "range_to", nullable = false)
    val rangeTo: Instant,
    @Column(name = "initial_cash", nullable = false, precision = 19, scale = 4)
    val initialCash: BigDecimal,
    @Column(name = "fill_model", nullable = false, length = 32)
    val fillModel: String,
    @Column(name = "status", nullable = false, length = 20)
    val status: String,
    @Column(name = "event_count", nullable = false)
    val eventCount: Int,
    @Column(name = "final_cash", precision = 19, scale = 4)
    val finalCash: BigDecimal? = null,
    @Column(name = "abort_reason", length = 80)
    val abortReason: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    val detailJson: String? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "completed_at", nullable = false)
    val completedAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
