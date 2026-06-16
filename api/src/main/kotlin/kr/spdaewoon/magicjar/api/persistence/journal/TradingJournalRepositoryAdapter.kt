package kr.spdaewoon.magicjar.api.persistence.journal

import jakarta.persistence.criteria.Predicate
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.JournalNote
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [TradingJournalRepository] port JPA 구현 어댑터 — Flyway V29 정합 (ADR 0072 §9-2).
 *
 * 헌법 §6 (ADR 0051) 준수: helper 메서드는 이 클래스 private fun 위치.
 *
 * ### 멱등성 처리 (ADR 0072 §3-1 ux_trading_journal_source UQ)
 * DB UQ `(source_table, source_id, event_type)` 위반 시
 * [DataIntegrityViolationException] catch → log.warn + null 반환 (INSERT IGNORE 효과).
 * Kafka at-least-once 재처리 안전.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Component
class TradingJournalRepositoryAdapter(
    private val journalJpaRepository: TradingJournalJpaRepository,
    private val noteJpaRepository: JournalNoteJpaRepository,
) : TradingJournalRepository {
    private val log = LoggerFactory.getLogger(TradingJournalRepositoryAdapter::class.java)

    @Transactional
    override fun saveEntry(entry: TradingJournalEntry): TradingJournalEntry? =
        try {
            val entity = toEntity(entry)
            val saved = journalJpaRepository.save(entity)
            log.debug(
                "trading_journal INSERT: id={} userId={} eventType={} sourceTable={} sourceId={}",
                saved.id,
                saved.userId,
                saved.eventType,
                saved.sourceTable,
                saved.sourceId,
            )
            toDomain(saved)
        } catch (e: DataIntegrityViolationException) {
            log.warn(
                "trading_journal UQ 충돌 — 멱등성 skip: sourceTable={} sourceId={} eventType={}",
                entry.sourceTable,
                entry.sourceId,
                entry.eventType,
            )
            null
        }

    @Transactional(readOnly = true)
    override fun findEntries(
        userId: Long,
        from: Instant?,
        to: Instant?,
        eventTypes: List<JournalEventType>?,
        instrumentCode: String?,
        instrumentQuery: String?,
        instrumentCodes: Set<String>?,
        page: Int,
        size: Int,
    ): List<TradingJournalEntry> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))
        val jpaPage = selectQuery(userId, from, to, eventTypes, instrumentCode, instrumentQuery, instrumentCodes, pageable)
        return jpaPage.content.map { entity ->
            val notes =
                noteJpaRepository
                    .findByJournalEntryIdOrderByCreatedAtAsc(entity.id!!)
                    .map { noteToDomain(it) }
            toDomain(entity).copy(notes = notes)
        }
    }

    @Transactional(readOnly = true)
    override fun countEntries(
        userId: Long,
        from: Instant?,
        to: Instant?,
        eventTypes: List<JournalEventType>?,
        instrumentCode: String?,
        instrumentQuery: String?,
        instrumentCodes: Set<String>?,
    ): Long {
        val pageable = PageRequest.of(0, 1)
        return selectQuery(userId, from, to, eventTypes, instrumentCode, instrumentQuery, instrumentCodes, pageable).totalElements
    }

    @Transactional(readOnly = true)
    override fun findEntryById(
        id: Long,
        userId: Long,
    ): TradingJournalEntry? {
        val entity = journalJpaRepository.findByIdAndUserId(id, userId) ?: return null
        val notes = noteJpaRepository.findByJournalEntryIdOrderByCreatedAtAsc(id).map { noteToDomain(it) }
        return toDomain(entity).copy(notes = notes)
    }

    @Transactional
    override fun saveNote(note: JournalNote): JournalNote {
        val entity = noteToEntity(note)
        val saved = noteJpaRepository.save(entity)
        log.debug("journal_note INSERT: id={} userId={} entryId={}", saved.id, saved.userId, saved.journalEntryId)
        return noteToDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findNotesByEntryId(entryId: Long): List<JournalNote> =
        noteJpaRepository.findByJournalEntryIdOrderByCreatedAtAsc(entryId).map { noteToDomain(it) }

    // --- private helper (헌법 §6 — Repository interface 내 body 금지 → 여기에 위치) ---

    private fun selectQuery(
        userId: Long,
        from: Instant?,
        to: Instant?,
        eventTypes: List<JournalEventType>?,
        instrumentCode: String?,
        instrumentQuery: String?,
        instrumentCodes: Set<String>?,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<TradingJournalJpaEntity> {
        val typeNames = eventTypes?.map { it.name }
        val normalizedCode = instrumentCode?.trim()?.takeIf { it.isNotBlank() }
        val normalizedQuery = instrumentQuery?.trim()?.takeIf { it.isNotBlank() }
        val spec =
            Specification<TradingJournalJpaEntity> { root, _, cb ->
                val predicates = mutableListOf<Predicate>()
                predicates += cb.equal(root.get<Long>("userId"), userId)
                from?.let { predicates += cb.greaterThanOrEqualTo(root.get("occurredAt"), it) }
                to?.let { predicates += cb.lessThanOrEqualTo(root.get("occurredAt"), it) }
                if (!typeNames.isNullOrEmpty()) {
                    predicates += root.get<String>("eventType").`in`(typeNames)
                }
                if (normalizedCode != null) {
                    predicates += cb.equal(root.get<String>("instrumentCode"), normalizedCode)
                } else if (normalizedQuery != null) {
                    val queryPredicates = mutableListOf<Predicate>()
                    val codePattern = "%$normalizedQuery%"
                    val namePattern = "%${normalizedQuery.lowercase()}%"
                    queryPredicates += cb.like(root.get<String>("instrumentCode"), codePattern)
                    queryPredicates += cb.like(cb.lower(root.get<String>("instrumentName")), namePattern)
                    instrumentCodes?.takeIf { it.isNotEmpty() }?.let { codes ->
                        queryPredicates += root.get<String>("instrumentCode").`in`(codes)
                    }
                    predicates += cb.or(*queryPredicates.toTypedArray())
                }
                cb.and(*predicates.toTypedArray())
            }
        return journalJpaRepository.findAll(spec, pageable)
    }

    private fun toEntity(entry: TradingJournalEntry): TradingJournalJpaEntity =
        TradingJournalJpaEntity(
            id = entry.id,
            userId = entry.userId,
            eventType = entry.eventType.name,
            instrumentCode = entry.instrumentCode,
            instrumentName = entry.instrumentName,
            occurredAt = entry.occurredAt,
            summary = entry.summary,
            payload = entry.payload,
            sourceTable = entry.sourceTable,
            sourceId = entry.sourceId,
            realizedPnl = entry.realizedPnl,
            createdAt = entry.createdAt,
        )

    private fun toDomain(entity: TradingJournalJpaEntity): TradingJournalEntry =
        TradingJournalEntry(
            id = entity.id,
            userId = entity.userId,
            eventType = safeEventType(entity.eventType),
            instrumentCode = entity.instrumentCode,
            instrumentName = entity.instrumentName,
            occurredAt = entity.occurredAt,
            summary = entity.summary,
            payload = entity.payload,
            sourceTable = entity.sourceTable,
            sourceId = entity.sourceId,
            realizedPnl = entity.realizedPnl,
            createdAt = entity.createdAt,
        )

    private fun noteToEntity(note: JournalNote): JournalNoteJpaEntity =
        JournalNoteJpaEntity(
            id = note.id,
            userId = note.userId,
            journalEntryId = note.journalEntryId,
            noteText = note.noteText,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
        )

    private fun noteToDomain(entity: JournalNoteJpaEntity): JournalNote =
        JournalNote(
            id = entity.id,
            userId = entity.userId,
            journalEntryId = entity.journalEntryId,
            noteText = entity.noteText,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    private fun safeEventType(value: String): JournalEventType =
        runCatching { JournalEventType.valueOf(value) }.getOrElse {
            log.warn("알 수 없는 JournalEventType 값: {} → ORDER_FILLED fallback", value)
            JournalEventType.ORDER_FILLED
        }
}
