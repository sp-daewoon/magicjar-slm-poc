package kr.spdaewoon.magicjar.domain.user

import kotlinx.datetime.Instant

/**
 * KIS API 자격증명 도메인 (ADR 0046 §2-2).
 * AES-256-GCM 암호화 후 BYTEA로 영속 — 복호화는 SecretCipher 포트 위임.
 * appKeyEnc / secretKeyEnc 는 암호화된 바이트 배열 (DB BYTEA 정합).
 */
data class UserKisCredential(
    val userId: String,
    val accountType: AccountType,
    val accountNumber: String,
    val accountPrdtCd: String = "01",
    val appKeyEnc: ByteArray,
    val secretKeyEnc: ByteArray,
    val lastTokenIss: Instant?,
    val lastValidatedAt: Instant?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserKisCredential) return false
        return userId == other.userId && accountType == other.accountType
    }

    override fun hashCode(): Int = 31 * userId.hashCode() + accountType.hashCode()
}
