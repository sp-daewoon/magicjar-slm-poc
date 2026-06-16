package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * instrument_status_history JPA 엔티티 — consumer 모듈 read-only 사본 (ADR 0090 Phase 2 LOCK).
 *
 * 책임:
 *   - chart-curator `CandleGapDetectorAdapter.passesActiveGapAxes()` 시점별 status 조회 단일 진입점
 *   - read-only 사본 — write path는 market-data `InstrumentMasterRefreshJob`(api 모듈)이 단독 책임
 *
 * 영역 매트릭스 (ADR 0085 §책임영역 + ADR 0090 §2-2·§2-3):
 *   - chart-curator (consumer): read-only 사본 — 본 파일 + ConsumerInstrumentStatusHistoryJpaRepository
 *   - market-data (api): write 권위 — InstrumentStatusHistoryRepositoryAdapter (Phase 1 PR #571)
 *
 * Flyway V42 정합 — column 매트릭스 1:1 LOCK (api 측 InstrumentStatusHistoryJpaEntity 미러).
 *
 * 정합 ADR:
 *   - ADR 0017 Instrument Master 1급 시민
 *   - ADR 0085 chart-curator 영입 (영역 매트릭스 read-only 사본 LOCK)
 *   - ADR 0086 §12 4축 active gap detection 시점 정밀도
 *   - ADR 0090 instrument_status_history 신설 (Phase 2 chart-curator lens 통합)
 */
@Entity
@Table(name = "instrument_status_history")
class ConsumerInstrumentStatusHistoryJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L,
    @Column(name = "instrument_code", length = 12, nullable = false)
    val instrumentCode: String,
    @Column(name = "status", length = 32, nullable = false)
    val status: String,
    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant,
    @Column(name = "effective_until")
    val effectiveUntil: Instant? = null,
    @Column(name = "reason", columnDefinition = "TEXT")
    val reason: String? = null,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        instrumentCode = "",
        status = "NORMAL",
        effectiveFrom = Instant.EPOCH,
    )
}
