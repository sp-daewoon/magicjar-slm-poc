package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * audit_trail Spring Data JPA Repository.
 */
interface AuditTrailJpaRepository : JpaRepository<AuditTrailJpaEntity, Long>
