package kr.spdaewoon.magicjar.api.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * market_index_daily Spring Data JPA Repository — Flyway V86 정합.
 *
 * 헌법 §6 (ADR 0051) abstract fun only LOCK — body 가진 default fun 0건.
 * helper·UPSERT 로직은 [MarketIndexDailyRollupService] / 어댑터 내부에 둔다.
 *
 * UQ (index_code, trade_date) — 멱등 UPSERT 비즈니스 키.
 */
interface MarketIndexDailyJpaRepository : JpaRepository<MarketIndexDailyJpaEntity, Long> {
    /** UPSERT 시 기존 행 lookup (멱등). */
    fun findByIndexCodeAndTradeDate(
        indexCode: String,
        tradeDate: LocalDate,
    ): MarketIndexDailyJpaEntity?

    /**
     * 전일 종가 lookup — 대상일 이전 가장 최근 영업일 1행 (change_pct 분모 source).
     * 직전 행이 없으면 null → 산식이 (close − open)/open 으로 fallback (spec §3 V86 change_pct).
     */
    @Query(
        value = """
            SELECT *
            FROM market_index_daily
            WHERE index_code = :indexCode
              AND trade_date < :tradeDate
            ORDER BY trade_date DESC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findMostRecentBefore(
        @Param("indexCode") indexCode: String,
        @Param("tradeDate") tradeDate: LocalDate,
    ): MarketIndexDailyJpaEntity?
}
