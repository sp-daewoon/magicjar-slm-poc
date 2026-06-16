package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository

/**
 * instrument_alias Spring Data JPA Repository.
 */
interface InstrumentAliasJpaRepository : JpaRepository<InstrumentAliasJpaEntity, InstrumentAliasId> {
    fun findByCode(code: String): List<InstrumentAliasJpaEntity>

    fun deleteByCode(code: String)
}
