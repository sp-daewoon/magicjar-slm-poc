package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * instrument_status_history JPA 엔티티 (ADR 0090 Phase 1 LOCK).
 *
 * 정합 ADR:
 *   - ADR 0017 Instrument Master 1급 시민
 *   - ADR 0085 chart-curator 영입
 *   - ADR 0086 §12 4축 active gap detection 정밀도
 *   - ADR 0090 instrument_status_history 신설
 *
 * Flyway V42 정합 — column 매트릭스 1:1 LOCK.
 */
@Entity
@Table(name = "instrument_status_history")
class InstrumentStatusHistoryJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0L,
    @Column(name = "instrument_code", length = 12, nullable = false)
    val instrumentCode: String,
    @Column(name = "status", length = 32, nullable = false)
    val status: String,
    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant,
    @Column(name = "effective_until")
    var effectiveUntil: Instant? = null,
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
