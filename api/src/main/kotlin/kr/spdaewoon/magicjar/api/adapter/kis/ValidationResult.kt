package kr.spdaewoon.magicjar.api.adapter.kis

import java.time.Instant

/**
 * KIS OAuth tokenP 검증 결과 (ADR 0046 §2-3, spec §4-6 정합).
 */
sealed class ValidationResult {
    data class Success(
        val tokenIssuedAt: Instant,
    ) : ValidationResult()

    data class Failed(
        val httpStatus: Int,
    ) : ValidationResult()

    data object Unavailable : ValidationResult() // KIS 다운 (5xx · timeout)
}
