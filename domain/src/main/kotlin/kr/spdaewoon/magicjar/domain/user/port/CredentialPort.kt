package kr.spdaewoon.magicjar.domain.user.port

import kr.spdaewoon.magicjar.domain.user.AccountType
import kr.spdaewoon.magicjar.domain.user.UserKisCredential

/**
 * KIS 자격증명 조회 서비스 포트 (ADR 0046 §2-4 정합).
 * 복호화된 appKey·secretKey를 반환 — 실제 복호화는 SecretCipher 위임.
 */
interface CredentialPort {
    fun loadDecrypted(
        userId: String,
        accountType: AccountType,
    ): UserKisCredential?
}
