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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "scenario_event")
class ScenarioEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    val run: ScenarioRunJpaEntity,
    @Column(name = "sequence_no", nullable = false)
    val sequenceNo: Int,
    @Column(name = "virtual_time", nullable = false)
    val virtualTime: Instant,
    @Column(name = "type", nullable = false, length = 40)
    val type: String,
    @Column(name = "severity", nullable = false, length = 10)
    val severity: String,
    @Column(name = "title", nullable = false, length = 160)
    val title: String,
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    val message: String,
    @Column(name = "strategy_id")
    val strategyId: Long? = null,
    @Column(name = "strategy_name", length = 200)
    val strategyName: String? = null,
    @Column(name = "code", length = 8)
    val code: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb")
    val payloadJson: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
