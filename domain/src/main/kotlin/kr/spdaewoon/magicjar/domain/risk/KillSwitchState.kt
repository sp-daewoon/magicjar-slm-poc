package kr.spdaewoon.magicjar.domain.risk

import kotlinx.datetime.Instant

/**
 * KillSwitch 상태 도메인 (ADR 0024·0043).
 * enabled=true이면 신규 주문 전면 차단.
 */
data class KillSwitchState(
    val userId: String,
    val enabled: Boolean = false,
    val reason: String? = null,
    val updatedAt: Instant,
    val updatedBy: String? = null,
)

/**
 * Risk Guard 결과.
 */
sealed class GuardResult {
    object Allowed : GuardResult()

    data class Rejected(
        val reason: String,
    ) : GuardResult()
}

/**
 * 주문 가드 포트 — 주문 전 risk 검사 (ADR 0024 정합).
 */
interface OrderGuard {
    fun check(
        userId: String,
        code: String,
    ): GuardResult
}
