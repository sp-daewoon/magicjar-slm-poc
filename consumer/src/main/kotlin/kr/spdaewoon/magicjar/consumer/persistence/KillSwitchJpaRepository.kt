package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * kill_switch_state Spring Data JPA Repository (consumer 모듈).
 */
interface KillSwitchJpaRepository : JpaRepository<KillSwitchJpaEntity, String> {
    /**
     * KillSwitch enable upsert — 부팅 실패 시 auto-ON (ADR 0042 §2-5 + ADR 0043).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO kill_switch_state (user_id, enabled, reason, updated_at, updated_by)
            VALUES (:userId, true, :reason, now(), :updatedBy)
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
        reason: String?,
        updatedBy: String,
    )

    /**
     * user_account 존재 검증 — `kill_switch_state.user_id` FK 위반 사전 방지 (ADR 0086 hotfix).
     *
     * 사유:
     *   - `kill_switch_state.user_id`는 `user_account(user_id)` ON DELETE CASCADE FK (V1__baseline.sql §9).
     *   - chart-curator `CandleBackfillWorker`가 system userId(예: "system") 사용 시 user_account row 부재 →
     *     `ERROR: insert or update on table "kill_switch_state" violates foreign key constraint
     *      "fk_kill_switch_state_user"` (v3.6.17 dogfooding evidence).
     *   - 본 메서드로 enable 전 존재 검증 → 부재 시 caller가 Slack notice + audit skip 분기.
     *
     * 자본 영향 0: 데이터 정합 보강 + 주문 발행 0.
     */
    @Query(
        value = "SELECT EXISTS(SELECT 1 FROM user_account WHERE user_id = :userId)",
        nativeQuery = true,
    )
    fun existsUserAccount(userId: String): Boolean
}
