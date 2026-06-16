package kr.spdaewoon.magicjar.api.persistence.trend

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface MarketIndexSnapshotJpaRepository : JpaRepository<MarketIndexSnapshotJpaEntity, Long> {
    @Query(
        value = """
            SELECT *
            FROM market_index_snapshot
            WHERE code = :code
            ORDER BY ts DESC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findLatestByCode(
        @Param("code") code: String,
    ): MarketIndexSnapshotJpaEntity?

    @Query(
        value = """
            SELECT *
            FROM (
                SELECT DISTINCT ON (code) *
                FROM market_index_snapshot
                WHERE code IN (:codes)
                ORDER BY code, ts DESC
            ) latest
            ORDER BY code
        """,
        nativeQuery = true,
    )
    fun findLatestByCodes(
        @Param("codes") codes: Collection<String>,
    ): List<MarketIndexSnapshotJpaEntity>

    @Query(
        value = """
            SELECT *
            FROM (
                SELECT DISTINCT ON ((ts AT TIME ZONE 'Asia/Seoul')::date) *
                FROM market_index_snapshot
                WHERE code = :code
                ORDER BY (ts AT TIME ZONE 'Asia/Seoul')::date DESC, ts DESC
                LIMIT :limit
            ) daily
            ORDER BY ts ASC
        """,
        nativeQuery = true,
    )
    fun findDailySeries(
        @Param("code") code: String,
        @Param("limit") limit: Int,
    ): List<MarketIndexSnapshotJpaEntity>

    @Query(
        value = """
            SELECT *
            FROM (
                SELECT *
                FROM market_index_snapshot
                WHERE code = :code
                ORDER BY ts DESC
                LIMIT :limit
            ) snapshots
            ORDER BY ts ASC
        """,
        nativeQuery = true,
    )
    fun findSnapshotSeries(
        @Param("code") code: String,
        @Param("limit") limit: Int,
    ): List<MarketIndexSnapshotJpaEntity>

    /**
     * 지수 일봉 롤업 집계 — 특정 KST 영업일 1개 code 의 OHLC (spec 2026-06-16 §3 V86 source).
     *
     * `ts` 는 TIMESTAMPTZ(KST 의미) — `AT TIME ZONE 'Asia/Seoul'` 으로 KST 달력일을 잘라 그날치만 묶는다.
     * open=첫 틱(ts ASC 1번)·high=max·low=min·close=막 틱(ts DESC 1번). 그날 snapshot 0건이면 행 미반환.
     * 새 KIS 호출 0 — 이미 적재된 영속 snapshot 만 읽음 (ADR 0126 §3-6).
     */
    @Query(
        value = """
            SELECT
                (array_agg(price ORDER BY ts ASC))[1]  AS open,
                MAX(price)                              AS high,
                MIN(price)                              AS low,
                (array_agg(price ORDER BY ts DESC))[1] AS close
            FROM market_index_snapshot
            WHERE code = :code
              AND (ts AT TIME ZONE 'Asia/Seoul')::date = CAST(:tradeDate AS date)
            HAVING COUNT(*) > 0
        """,
        nativeQuery = true,
    )
    fun aggregateDailyOhlc(
        @Param("code") code: String,
        @Param("tradeDate") tradeDate: LocalDate,
    ): MarketIndexDailyOhlcProjection?

    /**
     * 특정 KST 영업일에 snapshot 이 존재하는 지수 code 목록 (롤업 대상 자동 탐색).
     */
    @Query(
        value = """
            SELECT DISTINCT code
            FROM market_index_snapshot
            WHERE (ts AT TIME ZONE 'Asia/Seoul')::date = CAST(:tradeDate AS date)
            ORDER BY code
        """,
        nativeQuery = true,
    )
    fun findDistinctCodesByTradeDate(
        @Param("tradeDate") tradeDate: LocalDate,
    ): List<String>
}

/**
 * 지수 일봉 OHLC 집계 결과 projection (Spring Data interface projection).
 * native query column alias(open/high/low/close)와 getter 명이 정합해야 한다.
 */
interface MarketIndexDailyOhlcProjection {
    fun getOpen(): java.math.BigDecimal?

    fun getHigh(): java.math.BigDecimal?

    fun getLow(): java.math.BigDecimal?

    fun getClose(): java.math.BigDecimal?
}
