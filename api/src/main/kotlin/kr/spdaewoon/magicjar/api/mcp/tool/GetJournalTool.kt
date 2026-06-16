package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.controller.dto.journal.TradingJournalEntryDto
import kr.spdaewoon.magicjar.api.mcp.McpTool
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalInt
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalString
import kr.spdaewoon.magicjar.api.service.journal.TradingJournalService
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * MCP tool `get_journal` — 실제 체결 매매일지 + summary.
 *
 * 기존 [TradingJournalService] reuse — modify 0 (영역 침범 0 LOCK).
 *
 * ## input
 * - `from`: ISO8601 (옵션)
 * - `to`: ISO8601 (옵션)
 * - `code`: 종목 코드 필터 (옵션)
 * - `query`: 종목명 또는 코드 조각 검색어 (옵션)
 * - `eventTypes`: 이벤트 타입 필터 (옵션 · ORDER_FILLED만 노출)
 * - `page`: 0-base (옵션 · default 0)
 * - `size`: 1~100 (옵션 · default 20)
 *
 * ## output
 * `{ entries: [...], total, page, size, summary: { ... } }`
 */
@Component
class GetJournalTool(
    private val journalService: TradingJournalService,
) : McpTool {
    override val name: String = "get_journal"
    override val description: String =
        "실제 매수/매도 체결 매매일지 조회 + summary (총 row 수 · 실현 손익 합계)."

    override val inputSchema: Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "from" to mapOf("type" to "string", "format" to "date-time", "description" to "기간 시작 (옵션)"),
                    "to" to mapOf("type" to "string", "format" to "date-time", "description" to "기간 종료 (옵션)"),
                    "code" to mapOf("type" to "string", "description" to "종목 코드 필터 (옵션)"),
                    "query" to mapOf("type" to "string", "description" to "종목명 또는 코드 조각 검색어 (옵션)"),
                    "eventTypes" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "이벤트 타입 필터 (ORDER_FILLED만 노출)",
                        ),
                    "page" to mapOf("type" to "integer", "minimum" to 0, "default" to 0),
                    "size" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100, "default" to 20),
                ),
            "required" to emptyList<String>(),
        )

    @Suppress("UNCHECKED_CAST")
    override fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?> {
        val userIdLong =
            userId.toLongOrNull()
                ?: throw McpToolException("MCP user id 정수 변환 실패: '$userId'")
        val from = parseOffsetDateTime(params.optionalString("from"), "from")
        val to = parseOffsetDateTime(params.optionalString("to"), "to")
        val code = params.optionalString("code")
        val query = params.optionalString("query")
        val eventTypes = (params["eventTypes"] as? List<*>)?.map { it?.toString() ?: "" }?.filter { it.isNotBlank() }
        val page = params.optionalInt("page", default = 0, min = 0) ?: 0
        val size = params.optionalInt("size", default = 20, min = 1, max = 100) ?: 20

        val pageResult = journalService.listEntries(userIdLong, from, to, eventTypes, code, query, page, size)
        val entries = pageResult.content

        return mapOf(
            "entries" to entries.map { it.toMap() },
            "total" to pageResult.totalElements,
            "page" to pageResult.number,
            "size" to pageResult.size,
            "summary" to buildSummary(entries),
        )
    }

    private fun TradingJournalEntryDto.toMap(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "eventType" to eventType,
            "instrumentCode" to instrumentCode,
            "instrumentName" to instrumentName,
            "occurredAt" to occurredAt.toString(),
            "summary" to summary,
            "realizedPnl" to realizedPnl?.toPlainString(),
            "payload" to payload,
            "notes" to
                notes.map {
                    mapOf(
                        "id" to it.id,
                        "noteText" to it.noteText,
                        "createdAt" to it.createdAt.toString(),
                        "updatedAt" to it.updatedAt.toString(),
                    )
                },
        )

    private fun buildSummary(entries: List<TradingJournalEntryDto>): Map<String, Any?> {
        val totalPnl =
            entries
                .mapNotNull { it.realizedPnl }
                .fold(BigDecimal.ZERO) { acc, v -> acc + v }
        val eventDistribution = entries.groupingBy { it.eventType }.eachCount()
        return mapOf(
            "totalRealizedPnl" to totalPnl.toPlainString(),
            "eventTypeDistribution" to eventDistribution,
            "uniqueInstrumentCount" to entries.mapNotNull { it.instrumentCode }.toSet().size,
        )
    }

    private fun parseOffsetDateTime(
        raw: String?,
        field: String,
    ): OffsetDateTime? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw)
        } catch (e: DateTimeParseException) {
            throw McpToolException("$field ISO8601 파싱 실패: '$raw'")
        }
    }
}
