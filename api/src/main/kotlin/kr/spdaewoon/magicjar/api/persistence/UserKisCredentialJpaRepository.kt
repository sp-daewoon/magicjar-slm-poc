package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * user_kis_credential Spring Data JPA Repository.
 */
interface UserKisCredentialJpaRepository : JpaRepository<UserKisCredentialJpaEntity, UserKisCredentialId> {
    @Query(
        "SELECT COUNT(c) > 0 FROM UserKisCredentialJpaEntity c WHERE c.id.userId = :userId AND c.id.accountType = :accountType",
    )
    fun existsByUserIdAndAccountType(
        userId: String,
        accountType: String,
    ): Boolean

    @Query("SELECT COUNT(c) > 0 FROM UserKisCredentialJpaEntity c WHERE c.id.accountType = 'MOCK'")
    fun existsMockAccount(): Boolean

    /**
     * 특정 계좌 유형의 모든 자격증명 목록 (ADR 0042 Bootstrap 활성 사용자 조회).
     */
    @Query("SELECT c FROM UserKisCredentialJpaEntity c WHERE c.id.accountType = :accountType")
    fun findAllByAccountType(accountType: String): List<UserKisCredentialJpaEntity>
}
