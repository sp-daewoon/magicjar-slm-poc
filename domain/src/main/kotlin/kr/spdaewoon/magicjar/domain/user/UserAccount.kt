package kr.spdaewoon.magicjar.domain.user

import kotlinx.datetime.Instant

/**
 * 사용자 계좌 도메인 (ADR 0046 §2-1).
 * userId = KIS 실전 계좌번호 8자리 (자연 키 — 단일 사용자 정합).
 */
data class UserAccount(
    val userId: String,
    val displayName: String?,
    val maskedAccount: String,
    val createdAt: Instant,
    val lastLoginAt: Instant?,
)

/**
 * KIS 계좌 유형 (ADR 0046 §2-2).
 * REAL = 실전, MOCK = 모의 (dev/test 전용).
 */
enum class AccountType { REAL, MOCK }
