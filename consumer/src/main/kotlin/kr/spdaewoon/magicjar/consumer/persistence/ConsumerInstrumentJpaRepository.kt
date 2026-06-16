package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * instrument Spring Data JPA Repository (consumer 모듈).
 * Bootstrap 시 stub upsert 전용.
 */
interface ConsumerInstrumentJpaRepository : JpaRepository<ConsumerInstrumentJpaEntity, String> {
    fun existsByCode(code: String): Boolean

    /**
     * instrument stub upsert — ON CONFLICT (code) DO NOTHING.
     * 이미 존재하면 skip (T-IM1 진입 시 정식 데이터로 갱신됨).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO instrument (code, name, market, type, active, created_at, updated_at)
            VALUES (:code, :code, 'UNKNOWN', 'UNKNOWN', true, now(), now())
            ON CONFLICT (code) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun upsertStub(code: String)
}
