package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * batch 모듈 instrument_status_history JPA repository (ADR 0090 Phase 1 LOCK).
 *
 * 헌법 §6 정합 (Repository default fun 함정 가드):
 *   - interface 내부 default fun body 0건 LOCK
 *   - 모든 method abstract — Spring Data JPA가 query derivation 또는 @Query 명시
 *
 * 정합 ADR:
 *   - ADR 0051 Repository default fun 함정 (헌법 §6 baseline)
 *   - ADR 0090 instrument_status_history 신설
 */
@Repository
interface BatchInstrumentStatusHistoryJpaRepository : JpaRepository<BatchInstrumentStatusHistoryJpaEntity, Long> {
    /** 종목코드의 현재 active row 조회 — effective_until IS NULL row. */
    @Query(
        """
        SELECT h FROM BatchInstrumentStatusHistoryJpaEntity h
        WHERE h.instrumentCode = :code
          AND h.effectiveUntil IS NULL
        """,
    )
    fun findActiveByCode(
        @Param("code") code: String,
    ): BatchInstrumentStatusHistoryJpaEntity?

    /** 기존 active row effective_until backfill (status 변화 detection 시). */
    @Modifying
    @Query(
        """
        UPDATE BatchInstrumentStatusHistoryJpaEntity h
        SET h.effectiveUntil = :effectiveUntil
        WHERE h.instrumentCode = :code
          AND h.effectiveUntil IS NULL
        """,
    )
    fun closeActiveStatus(
        @Param("code") code: String,
        @Param("effectiveUntil") effectiveUntil: Instant,
    ): Int

    /** baseline backfill — history 부재 종목 detection. */
    @Query(
        """
        SELECT h.instrumentCode FROM BatchInstrumentStatusHistoryJpaEntity h
        WHERE h.instrumentCode IN :codes
        """,
    )
    fun findExistingCodes(
        @Param("codes") codes: Collection<String>,
    ): List<String>
}
