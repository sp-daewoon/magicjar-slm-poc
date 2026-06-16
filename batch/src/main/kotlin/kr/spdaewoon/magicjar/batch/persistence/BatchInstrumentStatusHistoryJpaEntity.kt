package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * batch 모듈 instrument_status_history JPA 엔티티 (ADR 0090 Phase 1 LOCK).
 *
 * api 모듈 [kr.spdaewoon.magicjar.api.persistence.InstrumentStatusHistoryJpaEntity]와 동일 테이블 — 컬럼명 1:1 정합 의무.
 * batch 모듈은 InstrumentSyncJob status 변화 detection path 전용 — 모듈 경계 정합 (ADR 0004).
 */
@Entity
@Table(name = "instrument_status_history")
class BatchInstrumentStatusHistoryJpaEntity(
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
