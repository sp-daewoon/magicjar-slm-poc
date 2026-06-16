package kr.spdaewoon.magicjar.batch.persistence.market

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * market_index_snapshot Spring Data JPA Repository (batch 모듈 — T-MARKET Phase 1).
 *
 * ADR 0051 §2 + 헌법 §6 — body 가진 fun 0건 LOCK.
 * UPSERT는 nativeQuery (ON CONFLICT DO NOTHING — 1분 cron 멱등성 보장).
 */
interface MarketIndexSnapshotJpaRepository : JpaRepository<MarketIndexSnapshotJpaEntity, Long> {
    /**
     * 1분 snapshot 멱등 insert — UNIQUE(code, ts) 충돌 시 skip.
     *
     * @return inserted row count (0 = 충돌 skip, 1 = 신규 insert)
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO market_index_snapshot
                (code, market, ts, price, open, high, low, close, change_rate, volume, raw_payload, created_at)
            VALUES
                (:code, :market, :ts, :price, :open, :high, :low, :close, :changeRate, :volume,
                 CAST(:rawPayload AS jsonb), now())
            ON CONFLICT (code, ts) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("code") code: String,
        @Param("market") market: String,
        @Param("ts") ts: Instant,
        @Param("price") price: BigDecimal,
        @Param("open") open: BigDecimal?,
        @Param("high") high: BigDecimal?,
        @Param("low") low: BigDecimal?,
        @Param("close") close: BigDecimal?,
        @Param("changeRate") changeRate: BigDecimal?,
        @Param("volume") volume: Long?,
        @Param("rawPayload") rawPayload: String?,
    ): Int

    /**
     * 최신 N건 조회 (code 기준 ts DESC).
     */
    @Query(
        value = """
            SELECT * FROM market_index_snapshot
            WHERE code = :code
            ORDER BY ts DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findLatest(
        @Param("code") code: String,
        @Param("limit") limit: Int,
    ): List<MarketIndexSnapshotJpaEntity>

    /**
     * since 이후 모든 snapshot 조회 (code 기준 ts ASC).
     */
    @Query("SELECT s FROM MarketIndexSnapshotJpaEntity s WHERE s.code = :code AND s.ts >= :since ORDER BY s.ts ASC")
    fun findSince(
        @Param("code") code: String,
        @Param("since") since: Instant,
    ): List<MarketIndexSnapshotJpaEntity>
}
