package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * risk_event JPA entity copy for the consumer JVM.
 */
@Entity
@Table(name = "risk_event")
class ConsumerRiskEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "signal_id")
    val signalId: Long? = null,
    @Column(name = "rule", nullable = false, length = 40)
    val rule: String,
    @Column(name = "verdict", nullable = false, length = 20)
    val verdict: String,
    @Column(name = "reason", length = 500)
    val reason: String? = null,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = "",
        rule = "USER_DISABLED",
        verdict = "BLOCKED",
        ts = Instant.EPOCH,
    )
}
