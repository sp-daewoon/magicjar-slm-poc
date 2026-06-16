package kr.spdaewoon.magicjar.batch.persistence

import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpAction
import kr.spdaewoon.magicjar.domain.instrument.corpaction.CorpActionType
import kr.spdaewoon.magicjar.domain.instrument.corpaction.port.CorpActionRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

/**
 * CorpActionRepository port 구현 (batch 모듈).
 *
 * JPA upsert는 nativeQuery ON CONFLICT — ADR 0051 §2 정합.
 */
@Component
class CorpActionRepositoryAdapter(
    private val jpaRepository: CorpActionJpaRepository,
) : CorpActionRepository {
    override fun upsert(action: CorpAction): CorpAction {
        jpaRepository.upsert(
            instrumentCode = action.instrumentCode,
            type = action.type.name,
            exDate = action.exDate.toString(),
            ratio = action.ratio,
            cashDividend = action.cashDividend,
            mergeRatio = action.mergeRatio,
            source = action.source,
            rawPayload = action.rawPayload,
            appliedAt = action.appliedAt,
        )
        return action
    }

    override fun findByCodeAndExDate(
        code: String,
        exDate: LocalDate,
    ): List<CorpAction> =
        jpaRepository
            .findByInstrumentCodeAndExDate(code, exDate)
            .map { it.toDomain() }

    override fun findPendingApplied(): List<CorpAction> = jpaRepository.findPendingApplied().map { it.toDomain() }

    override fun markApplied(
        id: Long,
        at: Instant,
    ) {
        jpaRepository.markApplied(id, at)
    }

    private fun CorpActionJpaEntity.toDomain(): CorpAction =
        CorpAction(
            id = this.id ?: 0L,
            instrumentCode = this.instrumentCode,
            type = CorpActionType.valueOf(this.type),
            exDate = this.exDate,
            ratio = this.ratio,
            cashDividend = this.cashDividend,
            mergeRatio = this.mergeRatio,
            source = this.source,
            rawPayload = this.rawPayload,
            appliedAt = this.appliedAt,
            createdAt = this.createdAt,
        )
}
