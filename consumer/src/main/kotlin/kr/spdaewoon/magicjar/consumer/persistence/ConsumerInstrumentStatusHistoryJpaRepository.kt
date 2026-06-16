package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * instrument_status_history JPA repository — consumer 모듈 read-only 사본 (ADR 0090 Phase 2 LOCK).
 *
 * 책임:
 *   - chart-curator `passesActiveGapAxes()` 시점별 active status 조회 단일 진입점
 *   - write method 발행 0건 — consumer는 read-only 사본 LOCK (영역 매트릭스 정합)
 *
 * 헌법 §6 (Repository default fun 함정 가드):
 *   - interface 내부 default fun body 0건 LOCK
 *   - 모든 method abstract — @Query 명시
 *
 * 영역 매트릭스 (ADR 0085 §책임영역):
 *   - read-only 사본 (consumer/chart-curator) — 본 파일
 *   - write 권위 (api/market-data) — InstrumentStatusHistoryJpaRepository
 *
 * 정합 ADR:
 *   - ADR 0051 Repository default fun 함정 (헌법 §6 baseline)
 *   - ADR 0085 chart-curator 영입 (영역 매트릭스 read-only 사본 LOCK)
 *   - ADR 0090 instrument_status_history Phase 2 chart-curator lens 통합
 */
interface ConsumerInstrumentStatusHistoryJpaRepository : JpaRepository<ConsumerInstrumentStatusHistoryJpaEntity, Long> {
    /**
     * 종목코드의 특정 시점 active row 조회 — chart-curator §12 axis 2·3 권위 path.
     *
     * 알고리즘:
     *   - effective_from <= ts AND (effective_until > ts OR effective_until IS NULL)
     *   - effective_from DESC 정렬 — 가장 최근 해당 row 1건
     *
     * 호출 한계:
     *   - ts에 해당하는 active row가 없으면 emptyList → caller가 fallback 결정
     *   - active row가 여럿이면 (history immutability 위반 — 정상 운영에서는 0건) 가장 최근 row 채택
     */
    @Query(
        """
        SELECT h FROM ConsumerInstrumentStatusHistoryJpaEntity h
        WHERE h.instrumentCode = :code
          AND h.effectiveFrom <= :ts
          AND (h.effectiveUntil IS NULL OR h.effectiveUntil > :ts)
        ORDER BY h.effectiveFrom DESC
        """,
    )
    fun findStatusAt(
        @Param("code") code: String,
        @Param("ts") ts: Instant,
    ): List<ConsumerInstrumentStatusHistoryJpaEntity>
}
