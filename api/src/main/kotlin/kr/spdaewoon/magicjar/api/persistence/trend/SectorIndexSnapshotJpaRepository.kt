package kr.spdaewoon.magicjar.api.persistence.trend

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SectorIndexSnapshotJpaRepository : JpaRepository<SectorIndexSnapshotJpaEntity, Long> {
    @Query(
        value = """
            SELECT DISTINCT ON (sector_code) *
            FROM sector_index_snapshot
            ORDER BY sector_code, ts DESC
        """,
        nativeQuery = true,
    )
    fun findLatestBySector(): List<SectorIndexSnapshotJpaEntity>

    @Query(
        value = """
            SELECT *
            FROM (
                SELECT DISTINCT ON (sector_code) *
                FROM sector_index_snapshot
                WHERE (ts AT TIME ZONE 'Asia/Seoul')::date < (now() AT TIME ZONE 'Asia/Seoul')::date
                ORDER BY sector_code, ts DESC
            ) previous
            ORDER BY sector_code
        """,
        nativeQuery = true,
    )
    fun findPreviousBusinessDayBySector(): List<SectorIndexSnapshotJpaEntity>
}
