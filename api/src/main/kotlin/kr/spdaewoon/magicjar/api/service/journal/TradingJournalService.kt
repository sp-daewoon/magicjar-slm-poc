package kr.spdaewoon.magicjar.api.service.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteDto
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteRequest
import kr.spdaewoon.magicjar.api.controller.dto.journal.TradingJournalEntryDto
import kr.spdaewoon.magicjar.api.messaging.journal.JournalSummaryComposer
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaRepository
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.JournalNote
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 매매일지 REST 서비스 — 실제 체결 복기장.
 *
 * REST endpoint backing:
 * - listEntries: 체결 페이징 + 필터
 * - findEntry: 단건 + notes 포함
 * - writeNote: 메모 작성
 *
 * userId 격리: findEntry에서 userId mismatch 시 404.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Service
class TradingJournalService(
    private val journalRepository: TradingJournalRepository,
    private val objectMapper: ObjectMapper,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val orderExecutionJpaRepository: OrderExecutionJpaRepository,
    private val summaryComposer: JournalSummaryComposer,
) {
    private val kstZone = ZoneId.of("Asia/Seoul")
    private val orderFilledQtyKeys =
        listOf("executedQty", "executedQuantity", "filledQuantity", "filledQty", "quantity")
    private val orderFilledPriceKeys = listOf("executedPrice", "filledPrice", "price")

    private data class JournalDisplayData(
        val instrumentName: String?,
        val summary: String,
        val payload: Map<String, Any?>,
    )

    /**
     * 매매일지 체결 페이징 조회.
     * [eventTypeNames] null 또는 빈 리스트 시 ORDER_FILLED만 조회한다.
     * [from]/[to] null 시 기간 제한 없음.
     */
    fun listEntries(
        userId: Long,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        eventTypeNames: List<String>?,
        instrumentCode: String?,
        instrumentQuery: String?,
        page: Int,
        size: Int,
    ): Page<TradingJournalEntryDto> {
        val fromInstant = from?.toInstant()
        val toInstant = to?.toInstant()
        val eventTypes = parseEventTypes(eventTypeNames)
        val instrumentCodes = findInstrumentCodesByQuery(instrumentCode, instrumentQuery)

        val total =
            journalRepository.countEntries(
                userId,
                fromInstant,
                toInstant,
                eventTypes,
                instrumentCode,
                instrumentQuery,
                instrumentCodes,
            )
        val entries =
            journalRepository.findEntries(
                userId,
                fromInstant,
                toInstant,
                eventTypes,
                instrumentCode,
                instrumentQuery,
                instrumentCodes,
                page,
                size,
            )
        val instrumentMap = findInstrumentMap(entries.mapNotNull { it.instrumentCode }.toSet())

        val dtos =
            entries.map { entry ->
                val displayData =
                    buildJournalDisplayData(
                        entry = entry,
                        instrument = instrumentMap[entry.instrumentCode],
                    )
                toDto(
                    id = entry.id!!,
                    eventType = entry.eventType.name,
                    instrumentCode = entry.instrumentCode,
                    instrumentName = displayData.instrumentName,
                    occurredAt = OffsetDateTime.ofInstant(entry.occurredAt, kstZone),
                    summary = displayData.summary,
                    realizedPnl = entry.realizedPnl,
                    payload = displayData.payload,
                    notes =
                        entry.notes.map { note ->
                            JournalNoteDto(
                                id = note.id!!,
                                noteText = note.noteText,
                                createdAt = OffsetDateTime.ofInstant(note.createdAt, kstZone),
                                updatedAt = OffsetDateTime.ofInstant(note.updatedAt, kstZone),
                            )
                        },
                )
            }

        val pageable = PageRequest.of(page, size)
        return PageImpl(dtos, pageable, total)
    }

    /**
     * 매매일지 entry 단건 상세 조회.
     * userId mismatch 시 404.
     */
    fun findEntry(
        id: Long,
        userId: Long,
    ): TradingJournalEntryDto {
        val entry =
            journalRepository.findEntryById(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "매매일지 entry를 찾을 수 없습니다. id=$id, userId=$userId")
        if (entry.eventType != JournalEventType.ORDER_FILLED) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "매매일지 체결 entry가 아닙니다. id=$id, userId=$userId")
        }

        val displayData =
            buildJournalDisplayData(
                entry = entry,
                instrument = findInstrument(entry.instrumentCode),
            )
        return toDto(
            id = entry.id!!,
            eventType = entry.eventType.name,
            instrumentCode = entry.instrumentCode,
            instrumentName = displayData.instrumentName,
            occurredAt = OffsetDateTime.ofInstant(entry.occurredAt, kstZone),
            summary = displayData.summary,
            realizedPnl = entry.realizedPnl,
            payload = displayData.payload,
            notes =
                entry.notes.map { note ->
                    JournalNoteDto(
                        id = note.id!!,
                        noteText = note.noteText,
                        createdAt = OffsetDateTime.ofInstant(note.createdAt, kstZone),
                        updatedAt = OffsetDateTime.ofInstant(note.updatedAt, kstZone),
                    )
                },
        )
    }

    /**
     * 매매일지 메모 작성.
     * entry userId 격리: entry 조회 후 userId mismatch 시 404.
     */
    fun writeNote(request: JournalNoteRequest): JournalNoteDto {
        val userId = request.userId!!
        val entryId = request.journalEntryId!!
        val noteText = request.noteText!!

        // entry 소유권 검증
        val entry =
            journalRepository.findEntryById(entryId, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "매매일지 entry를 찾을 수 없습니다. entryId=$entryId, userId=$userId")
        if (entry.eventType != JournalEventType.ORDER_FILLED) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "매매일지 체결 entry가 아닙니다. entryId=$entryId, userId=$userId")
        }

        val now = Instant.now()
        val note =
            JournalNote(
                userId = userId,
                journalEntryId = entryId,
                noteText = noteText,
                createdAt = now,
                updatedAt = now,
            )
        val saved = journalRepository.saveNote(note)

        return JournalNoteDto(
            id = saved.id!!,
            noteText = saved.noteText,
            createdAt = OffsetDateTime.ofInstant(saved.createdAt, kstZone),
            updatedAt = OffsetDateTime.ofInstant(saved.updatedAt, kstZone),
        )
    }

    // --- private helpers ---

    private fun buildJournalDisplayData(
        entry: TradingJournalEntry,
        instrument: InstrumentJpaEntity?,
    ): JournalDisplayData {
        val payload = parsePayload(entry.payload)
        val instrumentName = resolveInstrumentName(entry.instrumentCode, entry.instrumentName, instrument)
        if (entry.eventType != JournalEventType.ORDER_FILLED) {
            return JournalDisplayData(instrumentName = instrumentName, summary = entry.summary, payload = payload)
        }
        val execution = findOrderExecution(entry, payload)
        val enrichedPayload =
            enrichOrderFilledPayloadForRead(
                entry = entry,
                payload = payload,
                instrumentName = instrumentName,
                execution = execution,
            )
        return JournalDisplayData(
            instrumentName = instrumentName,
            summary = summaryComposer.compose(JournalEventType.ORDER_FILLED, enrichedPayload),
            payload = enrichedPayload,
        )
    }

    private fun findInstrumentMap(codes: Set<String>): Map<String, InstrumentJpaEntity> {
        if (codes.isEmpty()) return emptyMap()
        return runCatching {
            instrumentJpaRepository
                .findAllById(codes)
                .associateBy { it.code }
        }.getOrDefault(emptyMap())
    }

    private fun findInstrumentCodesByQuery(
        instrumentCode: String?,
        instrumentQuery: String?,
    ): Set<String>? {
        if (!instrumentCode.isNullOrBlank()) return null
        val query = instrumentQuery?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            instrumentJpaRepository
                .searchByNameOrCode(query, null, PageRequest.of(0, 200))
                .map { it.code }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun findInstrument(code: String?): InstrumentJpaEntity? {
        if (code.isNullOrBlank()) return null
        return runCatching {
            instrumentJpaRepository.findById(code).orElse(null)
        }.getOrNull()
    }

    private fun resolveInstrumentName(
        code: String?,
        storedName: String?,
        instrument: InstrumentJpaEntity?,
    ): String? {
        val normalizedCode = code?.trim()
        val normalizedStoredName = storedName?.trim()?.takeIf { it.isNotBlank() && it != normalizedCode }
        if (normalizedStoredName != null) return normalizedStoredName
        return instrument?.name?.trim()?.takeIf { it.isNotBlank() && it != normalizedCode }
    }

    private fun findOrderExecution(
        entry: TradingJournalEntry,
        payload: Map<String, Any?>,
    ): OrderExecutionJpaEntity? {
        val payloadOrderId = payloadLong(payload, "orderId")
        payloadLong(payload, "executionId")?.let { executionId ->
            findOrderExecutionById(executionId)?.let { return it }
        }
        if (entry.sourceTable == "order_execution") {
            findOrderExecutionById(entry.sourceId)?.let { execution ->
                if (payloadOrderId == null || execution.orderId == payloadOrderId) return execution
            }
        }
        return payloadOrderId
            ?.let { orderId -> findOrderExecutionByOrderId(orderId, entry.occurredAt) }
    }

    private fun findOrderExecutionById(id: Long): OrderExecutionJpaEntity? =
        runCatching {
            orderExecutionJpaRepository.findById(id).orElse(null)
        }.getOrNull()

    private fun findOrderExecutionByOrderId(
        orderId: Long,
        occurredAt: Instant,
    ): OrderExecutionJpaEntity? =
        runCatching {
            pickOrderExecution(orderExecutionJpaRepository.findByOrderIdOrderByExecutedAtAsc(orderId), occurredAt)
        }.getOrNull()

    private fun pickOrderExecution(
        executions: List<OrderExecutionJpaEntity>,
        occurredAt: Instant,
    ): OrderExecutionJpaEntity? {
        if (executions.isEmpty()) return null
        return executions.firstOrNull { it.executedAt == occurredAt }
            ?: executions.maxWithOrNull(compareBy<OrderExecutionJpaEntity> { it.executedAt }.thenBy { it.id ?: Long.MIN_VALUE })
    }

    private fun enrichOrderFilledPayloadForRead(
        entry: TradingJournalEntry,
        payload: Map<String, Any?>,
        instrumentName: String?,
        execution: OrderExecutionJpaEntity?,
    ): Map<String, Any?> {
        val code = payload["code"]?.toString()?.takeIf { it.isNotBlank() } ?: entry.instrumentCode
        val side = payload["side"]?.toString()?.takeIf { it.isNotBlank() } ?: "BUY"
        val qty = execution?.executedQty ?: payloadBigDecimalAnyOf(payload, orderFilledQtyKeys)
        val price = execution?.executedPrice ?: payloadBigDecimalAnyOf(payload, orderFilledPriceKeys)
        val extra = mutableMapOf<String, Any?>("side" to side)
        code?.let { extra["code"] = it }
        instrumentName?.let { extra["instrumentName"] = it }
        qty?.let {
            extra["executedQty"] = it
            extra["executedQuantity"] = it
        }
        price?.let { extra["executedPrice"] = it }
        execution?.id?.let { extra["executionId"] = it }
        execution?.orderId?.let { extra["orderId"] = it }
        execution?.executedAt?.let { extra["executedAt"] = it.toString() }
        return payload + extra
    }

    private fun parseEventTypes(names: List<String>?): List<JournalEventType>? {
        if (names.isNullOrEmpty()) return listOf(JournalEventType.ORDER_FILLED)
        val parsed =
            names
                .mapNotNull { name ->
                    runCatching { JournalEventType.valueOf(name.uppercase()) }.getOrNull()
                }.filter { it == JournalEventType.ORDER_FILLED }
        return parsed.ifEmpty { listOf(JournalEventType.ORDER_FILLED) }
    }

    private fun toDto(
        id: Long,
        eventType: String,
        instrumentCode: String?,
        instrumentName: String?,
        occurredAt: OffsetDateTime,
        summary: String,
        realizedPnl: BigDecimal?,
        payload: Map<String, Any?>,
        notes: List<JournalNoteDto>,
    ): TradingJournalEntryDto =
        TradingJournalEntryDto(
            id = id,
            eventType = eventType,
            instrumentCode = instrumentCode,
            instrumentName = instrumentName,
            occurredAt = occurredAt,
            summary = summary,
            realizedPnl = realizedPnl,
            payload = payload,
            notes = notes,
        )

    @Suppress("UNCHECKED_CAST")
    private fun parsePayload(payloadJson: String): Map<String, Any?> =
        try {
            objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            mapOf("raw" to payloadJson)
        }

    private fun payloadLong(
        payload: Map<String, Any?>,
        key: String,
    ): Long? =
        when (val v = payload[key]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

    private fun payloadBigDecimalAnyOf(
        payload: Map<String, Any?>,
        keys: List<String>,
    ): BigDecimal? = keys.firstNotNullOfOrNull { key -> payloadBigDecimal(payload, key) }

    private fun payloadBigDecimal(
        payload: Map<String, Any?>,
        key: String,
    ): BigDecimal? =
        when (val v = payload[key]) {
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> v.toBigDecimalOrNull()
            else -> null
        }
}
