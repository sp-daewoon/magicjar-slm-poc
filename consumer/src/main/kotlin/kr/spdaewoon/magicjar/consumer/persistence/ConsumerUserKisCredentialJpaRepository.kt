package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * consumer 모듈의 user_kis_credential JPA Repository.
 * Bootstrap 활성 사용자 조회 전용 (ADR 0042 §2-5).
 */
interface ConsumerUserKisCredentialJpaRepository : JpaRepository<ConsumerUserKisCredentialJpaEntity, ConsumerUserKisCredentialId> {
    @Query("SELECT c FROM ConsumerUserKisCredentialJpaEntity c WHERE c.id.accountType = :accountType")
    fun findAllByAccountType(accountType: String): List<ConsumerUserKisCredentialJpaEntity>

    /**
     * 외부(HTS) 체결통보 흡수 시 KIS 계좌번호(CANO) → 내부 userId 매핑용 (2026-06-05 자본 path fix).
     *
     * 한 계좌번호가 REAL·MOCK 양쪽 자격증명에 동시 등장할 수 있어 List 반환 (caller가 mode 정합 판정).
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query("SELECT c FROM ConsumerUserKisCredentialJpaEntity c WHERE c.accountNumber = :accountNumber")
    fun findAllByAccountNumber(accountNumber: String): List<ConsumerUserKisCredentialJpaEntity>
}
