package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * kill_switch_state JPA 엔티티 (consumer 모듈 — ADR 0043).
 * Bootstrap 실패 시 KillSwitch auto-ON (ADR 0042 §2-5).
 */
@Entity
@Table(name = "kill_switch_state")
class KillSwitchJpaEntity(
    @Id
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,
    @Column(name = "reason", length = 64)
    var reason: String? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Column(name = "updated_by", length = 40)
    var updatedBy: String? = null,
) {
    protected constructor() : this(userId = "")
}
