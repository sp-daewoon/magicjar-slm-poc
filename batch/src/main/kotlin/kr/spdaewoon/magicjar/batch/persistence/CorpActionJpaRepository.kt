package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * corp_action Spring Data JPA Repository (batch 모듈 — T-CA1 PR-29b).
 *
 * ADR 0051 §2 — JpaRepository body fun 금지: 인터페이스 추상 메서드만.
 * ON CONFLICT upsert는 nativeQuery 사용 (Spring Data 파생 메서드 대체 불가).
 */
interface CorpActionJpaRepository : JpaRepository<CorpActionJpaEntity, Long> {
    /**
     * 종목 + 권리락일 기준 조회 (source 무관 — KIS·DART 양쪽 반환).
     */
    @Query("SELECT c FROM CorpActionJpaEntity c WHERE c.instrumentCode = :code AND c.exDate = :exDate")
    fun findByInstrumentCodeAndExDate(
        @Param("code") code: String,
        @Param("exDate") exDate: LocalDate,
    ): List<CorpActionJpaEntity>

    /**
     * 미적용 건 전체 조회 (appliedAt IS NULL).
     */
    @Query("SELECT c FROM CorpActionJpaEntity c WHERE c.appliedAt IS NULL ORDER BY c.exDate ASC")
    fun findPendingApplied(): List<CorpActionJpaEntity>

    /**
     * ON CONFLICT upsert — UNIQUE(instrument_code, type, ex_date, source) 기준.
     * 동일 source 재수집 시 raw_payload·updated_at만 갱신 (멱등).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO corp_action (instrument_code, type, ex_date, ratio, cash_dividend, merge_ratio,
                                     source, raw_payload, applied_at, created_at, updated_at)
            VALUES (:instrumentCode, :type, CAST(:exDate AS date), :ratio, :cashDividend, :mergeRatio,
                    :source, CAST(:rawPayload AS jsonb), :appliedAt, now(), now())
            ON CONFLICT (instrument_code, type, ex_date, source) DO UPDATE SET
                ratio         = EXCLUDED.ratio,
                cash_dividend = EXCLUDED.cash_dividend,
                merge_ratio   = EXCLUDED.merge_ratio,
                raw_payload   = EXCLUDED.raw_payload,
                updated_at    = now()
        """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("instrumentCode") instrumentCode: String,
        @Param("type") type: String,
        @Param("exDate") exDate: String,
        @Param("ratio") ratio: java.math.BigDecimal?,
        @Param("cashDividend") cashDividend: java.math.BigDecimal?,
        @Param("mergeRatio") mergeRatio: java.math.BigDecimal?,
        @Param("source") source: String,
        @Param("rawPayload") rawPayload: String?,
        @Param("appliedAt") appliedAt: Instant?,
    ): Int

    /**
     * CandleAdjustmentJob 완료 시 appliedAt 갱신.
     */
    @Modifying
    @Transactional
    @Query(
        value = "UPDATE corp_action SET applied_at = :appliedAt, updated_at = now() WHERE id = :id",
        nativeQuery = true,
    )
    fun markApplied(
        @Param("id") id: Long,
        @Param("appliedAt") appliedAt: Instant,
    ): Int
}
