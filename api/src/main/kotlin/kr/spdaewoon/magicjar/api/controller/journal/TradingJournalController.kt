package kr.spdaewoon.magicjar.api.controller.journal

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteDto
import kr.spdaewoon.magicjar.api.controller.dto.journal.JournalNoteRequest
import kr.spdaewoon.magicjar.api.controller.dto.journal.TradingJournalEntryDto
import kr.spdaewoon.magicjar.api.service.journal.TradingJournalService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * 매매일지 REST 컨트롤러 — 실제 체결 복기장.
 *
 * ### 엔드포인트 3건
 * - GET  /api/journal/entries          — 체결 페이징 + 필터
 * - GET  /api/journal/entries/{id}     — entry 단건 상세 (notes 포함)
 * - POST /api/journal/notes            — 사용자 메모 작성
 *
 * userId mismatch 시 404 LOCK (ADR 0010 multi-tenant 격리 정합).
 * Swagger 4단 한국어 + example (사용자 메모리 feedback_swagger_pattern 정합).
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Tag(name = "Trading Journal", description = "실제 매수/매도 체결 복기장 + 사용자 메모")
@RestController
@RequestMapping("/api/journal")
class TradingJournalController(
    private val journalService: TradingJournalService,
) {
    private val log = LoggerFactory.getLogger(TradingJournalController::class.java)

    // ─── GET /api/journal/entries — 시계열 페이징 조회 ─────────────────────

    @Operation(
        summary = "매매일지 체결 조회 (페이징, 최신순)",
        description = """
            실제 주문 체결 ORDER_FILLED만 조회한다. 최신순(occurredAt DESC).
            필터: from·to(기간) + instrumentCode/instrumentQuery(종목) + eventTypes(ORDER_FILLED 호환 파라미터).
            trading_journal 0 row 환경에서 "매매일지가 비어있습니다" 빈 페이지 반환 (mock 합성 0).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "매매일지 페이지 (빈 경우 content=[]로 반환)"),
            ApiResponse(responseCode = "400", description = "userId·기간·페이지 파라미터 검증 실패"),
        ],
    )
    @GetMapping("/entries")
    fun listEntries(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "기간 시작 (ISO 8601, 옵션)", example = "2026-05-01T00:00:00+09:00")
        @RequestParam(required = false) from: OffsetDateTime?,
        @Parameter(description = "기간 종료 (ISO 8601, 옵션)", example = "2026-05-14T23:59:59+09:00")
        @RequestParam(required = false) to: OffsetDateTime?,
        @Parameter(
            description = "이벤트 타입 필터 (ORDER_FILLED만 노출)",
            example = "ORDER_FILLED",
        )
        @RequestParam(required = false) eventTypes: List<String>?,
        @Parameter(description = "종목 코드 필터 (옵션)", example = "005930")
        @RequestParam(required = false) instrumentCode: String?,
        @Parameter(description = "종목명 또는 코드 조각 검색어 (옵션)", example = "삼성")
        @RequestParam(required = false) instrumentQuery: String?,
        @Parameter(description = "페이지 번호 (0-base)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지당 row 수 (최대 100)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<TradingJournalEntryDto> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 100)
        log.debug(
            "listEntries: userId={} from={} to={} eventTypes={} code={} query={} page={} size={}",
            userId,
            from,
            to,
            eventTypes,
            instrumentCode,
            instrumentQuery,
            safePage,
            safeSize,
        )
        return journalService.listEntries(userId, from, to, eventTypes, instrumentCode, instrumentQuery, safePage, safeSize)
    }

    // ─── GET /api/journal/entries/{id} — entry 단건 상세 ───────────────────

    @Operation(
        summary = "매매일지 entry 단건 상세",
        description = "entry + 사용자 메모 풀세트. userId mismatch 또는 미존재 시 404.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "entry 상세 (notes 포함)",
                content = [Content(schema = Schema(implementation = TradingJournalEntryDto::class))],
            ),
            ApiResponse(responseCode = "404", description = "entry 미존재 또는 userId mismatch"),
        ],
    )
    @GetMapping("/entries/{id}")
    fun findEntry(
        @Parameter(description = "entry ID", example = "1001", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID (격리 검증)", example = "12345678", required = true)
        @RequestParam userId: Long,
    ): TradingJournalEntryDto {
        log.debug("findEntry: id={} userId={}", id, userId)
        return journalService.findEntry(id, userId)
    }

    // ─── POST /api/journal/notes — 사용자 메모 작성 ────────────────────────

    @Operation(
        summary = "매매일지 entry에 사용자 메모 작성",
        description = """
            사용자가 매매 회고 자유 텍스트 입력. 1 entry : N notes 가능.
            noteText 최대 4000자. entry userId mismatch 시 404.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "메모 작성 성공",
                content = [Content(schema = Schema(implementation = JournalNoteDto::class))],
            ),
            ApiResponse(responseCode = "400", description = "요청 본문 검증 실패 (userId·journalEntryId null, noteText blank·초과)"),
            ApiResponse(responseCode = "404", description = "entry 미존재 또는 userId mismatch"),
        ],
    )
    @PostMapping("/notes")
    fun writeNote(
        @Valid @RequestBody request: JournalNoteRequest,
    ): ResponseEntity<JournalNoteDto> {
        log.debug("writeNote: userId={} entryId={}", request.userId, request.journalEntryId)
        val saved = journalService.writeNote(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }
}
