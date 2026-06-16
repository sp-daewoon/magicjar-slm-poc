package kr.spdaewoon.magicjar.api.persistence.killswitch

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * kill_switch_state JPA 엔티티 (api 모듈 write 전용 — ADR 0043 + ADR 0070 §2-11).
 *
 * consumer 모듈의 KillSwitchJpaEntity와 동일 테이블 — api에서 upsert 전용.
 * 헌법 §6: body 가진 fun 0건.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Entity
@Table(name = "kill_switch_state")
class KillSwitchWriteEntity(
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

/**
 * kill_switch_state 쓰기 전용 리포지터리 (api 모듈 — ADR 0070 §2-11 KillSwitchTogglePort).
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
interface KillSwitchWriteJpaRepository : JpaRepository<KillSwitchWriteEntity, String> {
    /**
     * KillSwitch enable upsert — drift 검출 시 auto-ON (ADR 0043 + ADR 0070 §2-11 step 5).
     * idempotent — 이미 enabled=true여도 reason·updatedAt 갱신.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO kill_switch_state (user_id, enabled, reason, updated_at, updated_by)
            VALUES (:userId, true, :reason, now(), 'T-RECON-AUTO')
            ON CONFLICT (user_id) DO UPDATE
                SET enabled = true,
                    reason = EXCLUDED.reason,
                    updated_at = now(),
                    updated_by = EXCLUDED.updated_by
        """,
        nativeQuery = true,
    )
    fun enableKillSwitch(
        userId: String,
        reason: String,
    )
}

/**
 * [KillSwitchTogglePort] JPA 어댑터 (api 모듈 — ADR 0043 + ADR 0070 §2-11 step 5).
 *
 * drift 검출 시 KillSwitchAutoToggler(@KafkaListener consumer)가 호출.
 * Phase 2 backend-core 합류 — consumer KillSwitchAutoToggler의 KillSwitchTogglePort 구현.
 *
 * 헌법 §6 (ADR 0051): helper는 이 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class KillSwitchToggleAdapter(
    private val repo: KillSwitchWriteJpaRepository,
) : KillSwitchTogglePort {
    private val log = LoggerFactory.getLogger(KillSwitchToggleAdapter::class.java)

    @Transactional
    override fun engage(
        userId: String,
        reason: String,
    ) {
        try {
            repo.enableKillSwitch(userId, reason)
            log.warn(
                "KillSwitch ENGAGED: userId={}**** reason={}",
                userId.take(4),
                reason,
            )
        } catch (e: Exception) {
            log.error(
                "KillSwitchToggleAdapter.engage 실패: userId={}**** reason={} err={}",
                userId.take(4),
                reason,
                e.message,
            )
            throw e
        }
    }
}
