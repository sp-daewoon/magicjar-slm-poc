package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BatchUserKisCredentialJpaRepository : JpaRepository<BatchUserKisCredentialJpaEntity, BatchUserKisCredentialId> {
    @Query("SELECT c FROM BatchUserKisCredentialJpaEntity c WHERE c.id.accountType = :accountType")
    fun findAllByAccountType(accountType: String): List<BatchUserKisCredentialJpaEntity>
}
