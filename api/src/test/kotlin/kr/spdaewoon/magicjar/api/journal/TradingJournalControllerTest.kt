package kr.spdaewoon.magicjar.api.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteDto
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteRequest
import kr.spdaewoon.magicjar.api.controller.dto.journal.TradingJournalEntryDto
import kr.spdaewoon.magicjar.api.controller.journal.TradingJournalController
import kr.spdaewoon.magicjar.api.service.journal.TradingJournalService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageImpl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TradingJournalControllerTest — 5건 GREEN 의무 (spec §3-1).
 *
 * 1. GET /api/journal/entries 200 + filter
 * 2. GET /api/journal/entries/{id} 200
 * 3. GET /api/journal/entries/{id} 404 — userId mismatch
 * 4. POST /api/journal/notes 201
 * 5. POST /api/journal/notes 400 — noteText blank
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@WebMvcTest(controllers = [TradingJournalController::class])
class TradingJournalControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var journalService: TradingJournalService

    // OnboardingGuardFilter 의존성 Mock
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    private fun makeEntryDto(
        id: Long = 1001L,
        eventType: String = "ORDER_FILLED",
        notes: List<JournalNoteDto> = emptyList(),
    ): TradingJournalEntryDto =
        TradingJournalEntryDto(
            id = id,
            eventType = eventType,
            instrumentCode = "005930",
            instrumentName = "삼성전자",
            occurredAt = OffsetDateTime.of(2026, 5, 14, 14, 32, 18, 0, ZoneOffset.ofHours(9)),
            summary = "체결: 삼성전자 매수 100주 @ 75,000원",
            realizedPnl = BigDecimal("150000"),
            payload = mapOf("code" to "005930", "side" to "BUY"),
            notes = notes,
        )

    private fun makeNoteDto(id: Long = 501L): JournalNoteDto =
        JournalNoteDto(
            id = id,
            noteText = "RSI 과매도 진입 테스트",
            createdAt = OffsetDateTime.now(ZoneOffset.ofHours(9)),
            updatedAt = OffsetDateTime.now(ZoneOffset.ofHours(9)),
        )

    @Test
    fun `GET entries 200 — 페이징 조회 정상`() {
        stubOnboarding()
        val entries = listOf(makeEntryDto(id = 3L), makeEntryDto(id = 2L))
        whenever(
            journalService.listEntries(
                eq(12345678L),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(0),
                eq(20),
            ),
        ).thenReturn(PageImpl(entries))

        mockMvc
            .get("/api/journal/entries") {
                param("userId", "12345678")
            }.andExpect {
                status { isOk() }
                jsonPath("$.content") { isArray() }
                jsonPath("$.content.length()") { value(2) }
                jsonPath("$.content[0].id") { value(3) }
                jsonPath("$.content[0].eventType") { value("ORDER_FILLED") }
            }
    }

    @Test
    fun `GET entries id 200 — entry 단건 정상 조회`() {
        stubOnboarding()
        val note = makeNoteDto()
        val entry = makeEntryDto(notes = listOf(note))
        whenever(journalService.findEntry(1001L, 12345678L)).thenReturn(entry)

        mockMvc
            .get("/api/journal/entries/1001") {
                param("userId", "12345678")
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1001) }
                jsonPath("$.notes.length()") { value(1) }
                jsonPath("$.notes[0].noteText") { value("RSI 과매도 진입 테스트") }
            }
    }

    @Test
    fun `GET entries id 404 — userId mismatch`() {
        stubOnboarding()
        whenever(journalService.findEntry(1001L, 99999L))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "entry not found"))

        mockMvc
            .get("/api/journal/entries/1001") {
                param("userId", "99999")
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `POST notes 201 — 메모 작성 성공`() {
        stubOnboarding()
        val savedNote = makeNoteDto()
        whenever(journalService.writeNote(any())).thenReturn(savedNote)

        val request =
            JournalNoteRequest(
                userId = 12345678L,
                journalEntryId = 1001L,
                noteText = "RSI 과매도 진입 테스트",
            )

        mockMvc
            .post("/api/journal/notes") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(501) }
                jsonPath("$.noteText") { value("RSI 과매도 진입 테스트") }
            }
    }

    @Test
    fun `POST notes 400 — noteText blank 검증 실패`() {
        stubOnboarding()

        val request =
            mapOf(
                "userId" to 12345678L,
                "journalEntryId" to 1001L,
                "noteText" to "   ", // blank
            )

        mockMvc
            .post("/api/journal/notes") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isBadRequest() }
            }
    }
}
