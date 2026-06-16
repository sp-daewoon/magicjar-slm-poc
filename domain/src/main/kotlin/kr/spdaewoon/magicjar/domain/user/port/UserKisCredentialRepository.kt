package kr.spdaewoon.magicjar.domain.user.port

import kr.spdaewoon.magicjar.domain.user.AccountType
import kr.spdaewoon.magicjar.domain.user.UserKisCredential

/**
 * KIS 자격증명 영속 포트 (ADR 0046 §2-2).
 */
interface UserKisCredentialRepository {
    fun findByUserIdAndType(
        userId: String,
        accountType: AccountType,
    ): UserKisCredential?

    /**
     * 특정 계좌 유형의 모든 자격증명 조회 (ADR 0042 Bootstrap 활성 사용자 목록).
     * @param accountType "REAL" 또는 "MOCK"
     */
    fun findAllByAccountType(accountType: AccountType): List<UserKisCredential>

    fun upsert(credential: UserKisCredential): UserKisCredential

    fun deleteByUserId(userId: String)
}
