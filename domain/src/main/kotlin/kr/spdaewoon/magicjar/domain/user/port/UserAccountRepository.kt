package kr.spdaewoon.magicjar.domain.user.port

import kr.spdaewoon.magicjar.domain.user.UserAccount

/**
 * 사용자 계좌 영속 포트 (ADR 0004 헥사고날).
 * 구현은 api 모듈 persistence 어댑터에서.
 */
interface UserAccountRepository {
    fun findById(userId: String): UserAccount?

    fun findActiveTradingUsers(): List<UserAccount>

    fun upsert(account: UserAccount): UserAccount
}
