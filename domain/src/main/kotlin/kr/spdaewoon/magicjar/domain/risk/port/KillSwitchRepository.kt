package kr.spdaewoon.magicjar.domain.risk.port

import kr.spdaewoon.magicjar.domain.risk.KillSwitchState

/**
 * KillSwitch 영속 포트 (ADR 0024·0043).
 */
interface KillSwitchRepository {
    fun findByUserId(userId: String): KillSwitchState?

    fun upsert(state: KillSwitchState): KillSwitchState

    fun enable(
        userId: String,
        reason: String,
        updatedBy: String,
    ): KillSwitchState

    fun disable(
        userId: String,
        updatedBy: String,
    ): KillSwitchState
}
