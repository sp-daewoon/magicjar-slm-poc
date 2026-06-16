package kr.spdaewoon.magicjar.batch.persistence.market

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * sector_index_snapshot Spring Data JPA Repository (batch 모듈 — T-MARKET Phase 1).
 *
 * ADR 0051 §2 + 헌법 §6 — body 가진 fun 0건 LOCK.
 */
interface SectorIndexSnapshotJpaRepository : JpaRepository<SectorIndexSnapshotJpaEntity, Long> {
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO sector_index_snapshot
                (sector_code, sector_name, ts, price, change_rate, trade_value, raw_payload, created_at)
            VALUES
                (:sectorCode, :sectorName, :ts, :price, :changeRate, :tradeValue,
                 CAST(:rawPayload AS jsonb), now())
            ON CONFLICT (sector_code, ts) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("sectorCode") sectorCode: String,
        @Param("sectorName") sectorName: String,
        @Param("ts") ts: Instant,
        @Param("price") price: BigDecimal,
        @Param("changeRate") changeRate: BigDecimal,
        @Param("tradeValue") tradeValue: BigDecimal?,
        @Param("rawPayload") rawPayload: String?,
    ): Int

    @Query("SELECT s FROM SectorIndexSnapshotJpaEntity s WHERE s.sectorCode = :code ORDER BY s.ts DESC")
    fun findLatestByCode(
        @Param("code") code: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<SectorIndexSnapshotJpaEntity>

    @Query("SELECT s FROM SectorIndexSnapshotJpaEntity s WHERE s.sectorCode = :code AND s.ts >= :since ORDER BY s.ts ASC")
    fun findSince(
        @Param("code") code: String,
        @Param("since") since: Instant,
    ): List<SectorIndexSnapshotJpaEntity>

    /**
     * 모든 섹터의 최신 1건 일괄 조회 — Postgres DISTINCT ON 활용.
     */
    @Query(
        value = """
            SELECT DISTINCT ON (sector_code) *
            FROM sector_index_snapshot
            ORDER BY sector_code, ts DESC
        """,
        nativeQuery = true,
    )
    fun findAllLatest(): List<SectorIndexSnapshotJpaEntity>
}
