package kr.spdaewoon.magicjar.api.persistence.order

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * kill_switch_state 읽기 전용 JPA 엔티티 (api 모듈 — ADR 0068 §15-2 KillSwitchQueryAdapter).
 *
 * consumer 모듈의 [KillSwitchJpaEntity]와 같은 테이블을 api에서 read-only로 참조.
 * 동일 테이블 2 엔티티 — 각 모듈 독립 컨텍스트 (Spring 모듈 분리 정합).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Entity
@Table(name = "kill_switch_state")
class KillSwitchReadEntity(
    @Id
    @Column(name = "user_id", length = 20, nullable = false, updatable = false)
    val userId: String,
    @Column(name = "enabled", nullable = false, insertable = false, updatable = false)
    val enabled: Boolean = false,
    @Column(name = "reason", length = 64, insertable = false, updatable = false)
    val reason: String? = null,
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(userId = "")
}

/**
 * kill_switch_state 읽기 전용 리포지터리 (api 모듈).
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 */
interface KillSwitchQueryJpaRepository : JpaRepository<KillSwitchReadEntity, String>
