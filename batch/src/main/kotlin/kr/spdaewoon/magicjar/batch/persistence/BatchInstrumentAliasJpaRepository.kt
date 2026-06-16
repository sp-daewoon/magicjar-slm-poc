package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * instrument_alias Spring Data JPA Repository (batch 모듈 — T-IM1 sync 전용).
 */
interface BatchInstrumentAliasJpaRepository : JpaRepository<BatchInstrumentAliasJpaEntity, InstrumentAliasId> {
    /**
     * alias upsert — 중복 시 skip (ON CONFLICT DO NOTHING).
     * KRX source: 종목명 그대로 1건/종목 default.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO instrument_alias (code, alias, source)
            VALUES (:code, :alias, :source)
            ON CONFLICT (code, alias, source) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun upsert(
        code: String,
        alias: String,
        source: String,
    )
}
