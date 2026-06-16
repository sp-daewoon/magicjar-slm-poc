package kr.spdaewoon.magicjar.api.infrastructure.instrument

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.persistence.InstrumentStatusHistoryJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentStatusHistoryJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.InstrumentStatus
import kr.spdaewoon.magicjar.domain.instrument.InstrumentStatusHistory
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentStatusHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant as JavaInstant
import kotlinx.datetime.Instant as KtInstant

/**
 * InstrumentStatusHistoryRepository 도메인 port adapter (ADR 0090 Phase 1 LOCK).
 *
 * 책임:
 *   - domain port [InstrumentStatusHistoryRepository] → JPA repository 위임
 *   - kotlinx.datetime.Instant ↔ java.time.Instant 변환 단일 책임
 *   - status enum ↔ String 변환 단일 책임 (CHECK constraint 정합)
 *
 * 정합 ADR:
 *   - ADR 0017 Instrument Master 1급 시민
 *   - ADR 0090 instrument_status_history 신설
 *   - ADR 0088 persistence-shared 패턴 (Phase 1 옵션 a — api 단독, Phase 2 reuse 검토)
 *
 * 헌법 §6 (Repository default fun 함정 가드):
 *   - JPA repository [InstrumentStatusHistoryJpaRepository]는 abstract fun + @Query만
 *   - 본 adapter가 domain ↔ JPA 변환 + 비즈니스 logic 단일 책임
 */
@Component
class InstrumentStatusHistoryRepositoryAdapter(
    private val jpaRepository: InstrumentStatusHistoryJpaRepository,
) : InstrumentStatusHistoryRepository {
    private val log = LoggerFactory.getLogger(InstrumentStatusHistoryRepositoryAdapter::class.java)

    @Transactional
    override fun insert(history: InstrumentStatusHistory): InstrumentStatusHistory {
        val entity =
            InstrumentStatusHistoryJpaEntity(
                instrumentCode = history.instrumentCode,
                status = history.status.name,
                effectiveFrom = history.effectiveFrom.toJavaInstant(),
                effectiveUntil = history.effectiveUntil?.toJavaInstant(),
                reason = history.reason,
                recordedAt = history.recordedAt.toJavaInstant(),
            )
        val saved = jpaRepository.save(entity)
        log.debug(
            "[InstrumentStatusHistory] INSERT: code={} status={} effectiveFrom={}",
            saved.instrumentCode,
            saved.status,
            saved.effectiveFrom,
        )
        return saved.toDomain()
    }

    @Transactional(readOnly = true)
    override fun findActiveByCode(instrumentCode: String): InstrumentStatusHistory? =
        jpaRepository.findActiveByCode(instrumentCode)?.toDomain()

    @Transactional(readOnly = true)
    override fun findStatusAt(
        instrumentCode: String,
        ts: KtInstant,
    ): InstrumentStatus? {
        val rows = jpaRepository.findStatusAt(instrumentCode, ts.toJavaInstant())
        val active = rows.firstOrNull() ?: return null
        return runCatching { InstrumentStatus.valueOf(active.status) }.getOrNull()
    }

    @Transactional
    override fun closeActiveStatus(
        instrumentCode: String,
        effectiveUntil: KtInstant,
    ): Int {
        val updated = jpaRepository.closeActiveStatus(instrumentCode, effectiveUntil.toJavaInstant())
        if (updated > 0) {
            log.debug(
                "[InstrumentStatusHistory] CLOSE ACTIVE: code={} effectiveUntil={} updated={}",
                instrumentCode,
                effectiveUntil,
                updated,
            )
        }
        return updated
    }

    @Transactional
    override fun backfillBaselineForMissingCodes(
        codes: Collection<String>,
        effectiveFrom: KtInstant,
    ): Int {
        if (codes.isEmpty()) return 0
        val existingSet = jpaRepository.findExistingCodes(codes).toHashSet()
        val missingCodes = codes.filterNot { it in existingSet }
        if (missingCodes.isEmpty()) return 0

        val now = JavaInstant.now()
        val effectiveFromJava = effectiveFrom.toJavaInstant()
        val entities =
            missingCodes.map { code ->
                InstrumentStatusHistoryJpaEntity(
                    instrumentCode = code,
                    status = InstrumentStatus.NORMAL.name,
                    effectiveFrom = effectiveFromJava,
                    effectiveUntil = null,
                    reason = "ADR 0090 Phase 1 baseline backfill",
                    recordedAt = now,
                )
            }
        jpaRepository.saveAll(entities)
        log.info(
            "[InstrumentStatusHistory] baseline backfill: missing={} effectiveFrom={}",
            missingCodes.size,
            effectiveFrom,
        )
        return missingCodes.size
    }

    private fun InstrumentStatusHistoryJpaEntity.toDomain(): InstrumentStatusHistory =
        InstrumentStatusHistory(
            id = this.id,
            instrumentCode = this.instrumentCode,
            status = runCatching { InstrumentStatus.valueOf(this.status) }.getOrDefault(InstrumentStatus.NORMAL),
            effectiveFrom = this.effectiveFrom.toKotlinInstant(),
            effectiveUntil = this.effectiveUntil?.toKotlinInstant(),
            reason = this.reason,
            recordedAt = this.recordedAt.toKotlinInstant(),
        )
}
