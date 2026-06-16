package kr.spdaewoon.magicjar.api.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteRequest
import kr.spdaewoon.magicjar.api.messaging.journal.JournalSummaryComposer
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaEntity
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionJpaRepository
import kr.spdaewoon.magicjar.api.service.journal.TradingJournalService
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.JournalNote
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * TradingJournalServiceTest — 4건 GREEN 의무 (spec §3-1).
 *
 * - list 페이징 조회
 * - 기본 조회 ORDER_FILLED 한정
 * - entry 단건 + notes 포함
 * - note 작성
 * - userId 격리 (404)
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class TradingJournalServiceTest {
    private val journalRepository: TradingJournalRepository = mock()
    private val instrumentJpaRepository: InstrumentJpaRepository = mock()
    private val orderExecutionJpaRepository: OrderExecutionJpaRepository = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val summaryComposer = JournalSummaryComposer()
    private val sut =
        TradingJournalService(
            journalRepository,
            objectMapper,
            instrumentJpaRepository,
            orderExecutionJpaRepository,
            summaryComposer,
        )

    private fun makeEntry(
        id: Long = 1001L,
        userId: Long = 12345678L,
        eventType: JournalEventType = JournalEventType.ORDER_FILLED,
        instrumentName: String? = "삼성전자",
        instrumentCode: String = "005930",
        summary: String = "체결: 삼성전자 매수 100주 @ 75,000원",
        payload: String = """{"code":"005930","side":"BUY"}""",
        sourceId: Long = 101L,
        notes: List<JournalNote> = emptyList(),
    ): TradingJournalEntry =
        TradingJournalEntry(
            id = id,
            userId = userId,
            eventType = eventType,
            instrumentCode = instrumentCode,
            instrumentName = instrumentName,
            occurredAt = Instant.parse("2026-05-14T05:32:18Z"),
            summary = summary,
            payload = payload,
            sourceTable = "order_execution",
            sourceId = sourceId,
            realizedPnl = BigDecimal("150000"),
            notes = notes,
        )

    private fun makeNote(
        id: Long = 501L,
        entryId: Long = 1001L,
        userId: Long = 12345678L,
    ): JournalNote =
        JournalNote(
            id = id,
            userId = userId,
            journalEntryId = entryId,
            noteText = "RSI 과매도 진입 테스트",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `listEntries — 페이징 조회 200 반환`() {
        val entries = listOf(makeEntry(id = 3L), makeEntry(id = 2L), makeEntry(id = 1L))
        whenever(
            journalRepository.countEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq(null),
                eq(null),
            ),
        ).thenReturn(3L)
        whenever(
            journalRepository.findEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq(null),
                eq(null),
                eq(0),
                eq(20),
            ),
        ).thenReturn(entries)

        val result = sut.listEntries(12345678L, null, null, null, null, null, 0, 20)

        assertThat(result.content).hasSize(3)
        assertThat(result.totalElements).isEqualTo(3L)
        assertThat(result.content[0].id).isEqualTo(3L)
        assertThat(result.content[0].eventType).isEqualTo("ORDER_FILLED")
    }

    @Test
    fun `listEntries — 저장된 종목명이 비어있으면 instrument 마스터 이름으로 보강`() {
        val entry = makeEntry(instrumentName = null)
        whenever(
            journalRepository.countEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq(null),
                eq(null),
            ),
        ).thenReturn(1L)
        whenever(
            journalRepository.findEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq(null),
                eq(null),
                eq(0),
                eq(20),
            ),
        ).thenReturn(listOf(entry))
        whenever(instrumentJpaRepository.findAllById(eq(setOf("005930"))))
            .thenReturn(listOf(InstrumentJpaEntity(code = "005930", name = "삼성전자", market = "KOSPI", type = "STOCK")))

        val result = sut.listEntries(12345678L, null, null, null, null, null, 0, 20)

        assertThat(result.content[0].instrumentName).isEqualTo("삼성전자")
    }

    @Test
    fun `listEntries — 기존 payload 수량이 1이어도 order_execution 수량으로 보정한다`() {
        val entry =
            makeEntry(
                instrumentCode = "038110",
                instrumentName = null,
                summary = "체결: 038110 매수 1주 @ 1,000원",
                payload = """{"orderId":101,"code":"038110","side":"BUY","executedQty":1,"executedPrice":1000}""",
                sourceId = 101L,
            )
        val execution =
            OrderExecutionJpaEntity(
                id = 9001L,
                orderId = 101L,
                executedQty = BigDecimal("5"),
                executedPrice = BigDecimal("1000"),
                executedAt = Instant.parse("2026-05-14T05:32:18Z"),
            )
        whenever(
            journalRepository.countEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq(null),
                eq(null),
            ),
        ).thenReturn(1L)
        whenever(
            journalRepository.findEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq(null),
                eq(null),
                eq(0),
                eq(20),
            ),
        ).thenReturn(listOf(entry))
        whenever(instrumentJpaRepository.findAllById(eq(setOf("038110"))))
            .thenReturn(listOf(InstrumentJpaEntity(code = "038110", name = "에코플라스틱", market = "KOSDAQ", type = "STOCK")))
        whenever(orderExecutionJpaRepository.findById(101L)).thenReturn(Optional.empty())
        whenever(orderExecutionJpaRepository.findByOrderIdOrderByExecutedAtAsc(101L)).thenReturn(listOf(execution))

        val result = sut.listEntries(12345678L, null, null, null, null, null, 0, 20)

        assertThat(result.content[0].instrumentName).isEqualTo("에코플라스틱")
        assertThat(result.content[0].summary).contains("에코플라스틱 매수 5주")
        assertThat(BigDecimal(result.content[0].payload["executedQty"].toString())).isEqualByComparingTo(BigDecimal("5"))
        assertThat(result.content[0].payload["executionId"]).isEqualTo(9001L)
    }

    @Test
    fun `listEntries — 종목명 검색어는 instrument 마스터 코드 후보로 조회한다`() {
        whenever(instrumentJpaRepository.searchByNameOrCode(eq("에코"), eq(null), eq(PageRequest.of(0, 200))))
            .thenReturn(listOf(InstrumentJpaEntity(code = "038110", name = "에코플라스틱", market = "KOSDAQ", type = "STOCK")))
        whenever(
            journalRepository.countEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq("에코"),
                eq(setOf("038110")),
            ),
        ).thenReturn(0L)
        whenever(
            journalRepository.findEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(listOf(JournalEventType.ORDER_FILLED)),
                eq(null),
                eq("에코"),
                eq(setOf("038110")),
                eq(0),
                eq(20),
            ),
        ).thenReturn(emptyList())

        val result = sut.listEntries(12345678L, null, null, null, null, "에코", 0, 20)

        assertThat(result.content).isEmpty()
    }

    @Test
    fun `findEntry — entry 단건 + notes 포함 반환`() {
        val note = makeNote()
        val entry = makeEntry(notes = listOf(note))
        whenever(journalRepository.findEntryById(1001L, 12345678L)).thenReturn(entry)

        val result = sut.findEntry(1001L, 12345678L)

        assertThat(result.id).isEqualTo(1001L)
        assertThat(result.instrumentCode).isEqualTo("005930")
        assertThat(result.notes).hasSize(1)
        assertThat(result.notes[0].noteText).isEqualTo("RSI 과매도 진입 테스트")
    }

    @Test
    fun `writeNote — 메모 작성 성공`() {
        val entry = makeEntry()
        whenever(journalRepository.findEntryById(1001L, 12345678L)).thenReturn(entry)
        val savedNote = makeNote()
        whenever(journalRepository.saveNote(any())).thenReturn(savedNote)

        val request =
            JournalNoteRequest(
                userId = 12345678L,
                journalEntryId = 1001L,
                noteText = "RSI 과매도 진입 테스트",
            )
        val result = sut.writeNote(request)

        assertThat(result.id).isEqualTo(501L)
        assertThat(result.noteText).isEqualTo("RSI 과매도 진입 테스트")
    }

    @Test
    fun `findEntry — userId 격리 — mismatch 시 404`() {
        // userId=99999로 조회, entry 없음 (userId mismatch)
        whenever(journalRepository.findEntryById(1001L, 99999L)).thenReturn(null)

        val ex =
            assertThrows<ResponseStatusException> {
                sut.findEntry(1001L, 99999L)
            }
        assertThat(ex.statusCode.value()).isEqualTo(404)
    }
}
