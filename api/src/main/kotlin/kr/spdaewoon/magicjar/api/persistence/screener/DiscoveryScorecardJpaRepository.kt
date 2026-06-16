package kr.spdaewoon.magicjar.api.persistence.screener

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/**
 * discovery_scorecard Spring Data JPA Repository (Flyway V77).
 *
 * 헌법 §6 LOCK: interface 내부 body 가진 default fun 0건. abstract fun(@Query 포함) 단독 허용.
 *
 * - snapshot 잡: [insertIgnore] native ON CONFLICT DO NOTHING (멱등 — 같은 거래일 재발사 중복 0).
 * - 채점 잡: [findUnscoredBefore] 미채점 row 조회 → 엔티티 dirty-update (scored_at 마킹).
 * - 조회 REST: [findForSummary]·[findRowsInRange] 기간·프리셋 필터.
 */
interface DiscoveryScorecardJpaRepository : JpaRepository<DiscoveryScorecardJpaEntity, Long> {
    /**
     * 단건 idempotent INSERT (ON CONFLICT (snapshot_date, preset_key, instrument_code) DO NOTHING).
     *
     * @Transactional 필수 — @Modifying native query는 활성 트랜잭션 없이 실행 불가.
     * @return 실제 INSERT된 row 수 (0 = 이미 존재 — 멱등).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO discovery_scorecard
                (snapshot_date, preset_key, rank, instrument_code, instrument_name, score, base_close)
            VALUES
                (:snapshotDate, :presetKey, :rank, :instrumentCode, :instrumentName, :score, :baseClose)
            ON CONFLICT (snapshot_date, preset_key, instrument_code) DO NOTHING
        """,
    )
    fun insertIgnore(
        @Param("snapshotDate") snapshotDate: LocalDate,
        @Param("presetKey") presetKey: String,
        @Param("rank") rank: Int,
        @Param("instrumentCode") instrumentCode: String,
        @Param("instrumentName") instrumentName: String,
        @Param("score") score: BigDecimal,
        @Param("baseClose") baseClose: BigDecimal?,
    ): Int

    /**
     * 미채점(scored_at IS NULL) row 중 snapshot_date <= cutoff 인 것 — 채점 대상.
     * cutoff = 당일(다음 거래일 일봉이 채점 시점에 적재되어 있어야 채점 가능).
     */
    fun findByScoredAtIsNullAndSnapshotDateLessThanEqualOrderBySnapshotDateAsc(cutoff: LocalDate): List<DiscoveryScorecardJpaEntity>

    /** 기간 내 채점 완료 row (요약·hit-rate 집계용). */
    @Query(
        """
        SELECT e FROM DiscoveryScorecardJpaEntity e
        WHERE e.snapshotDate >= :from AND e.snapshotDate <= :to
          AND (:presetKey IS NULL OR e.presetKey = :presetKey)
        ORDER BY e.snapshotDate DESC, e.presetKey ASC, e.rank ASC
        """,
    )
    fun findRowsInRange(
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("presetKey") presetKey: String?,
    ): List<DiscoveryScorecardJpaEntity>
}
