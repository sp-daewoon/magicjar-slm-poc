package kr.spdaewoon.magicjar.consumer.persistence

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
 * signal JPA entity copy for the consumer JVM.
 */
@Entity
@Table(name = "signal")
class ConsumerSignalJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "code", nullable = false, length = 8)
    val code: String,
    @Column(name = "interval", nullable = false, length = 8)
    val interval: String,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "side", nullable = false, length = 4)
    val side: String,
    @Column(name = "strength", nullable = false)
    val strength: Int = 0,
    @Column(name = "reason", length = 500)
    val reason: String? = null,
    @Column(name = "price", precision = 19, scale = 4)
    val price: BigDecimal? = null,
    @Column(name = "rule_signal", length = 80)
    val ruleSignal: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", columnDefinition = "jsonb")
    val contextJson: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "risk_status", nullable = false, length = 20)
    val riskStatus: String = "PENDING",
) {
    protected constructor() : this(
        strategyId = 0L,
        userId = "",
        code = "",
        interval = "",
        ts = Instant.EPOCH,
        side = "HOLD",
    )
}
