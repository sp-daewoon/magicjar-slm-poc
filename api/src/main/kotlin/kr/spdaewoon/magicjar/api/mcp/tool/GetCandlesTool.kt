package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.controller.CandleDto
import kr.spdaewoon.magicjar.api.mcp.McpTool
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalInt
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalString
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.requireEnum
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.requireString
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import org.springframework.stereotype.Component

/**
 * MCP tool `get_candles` — OHLCV 캔들 query (adjusted default · ADR 0027 §2-2).
 *
 * 기존 [CandleQueryService] reuse — modify 0 (영역 침범 0 LOCK).
 *
 * ## input
 * - `code`: 6자리 종목코드 (예: "005930")
 * - `interval`: 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y 중)
 * - `before`: ISO8601 cursor (옵션 · 페이지네이션)
 * - `limit`: 1~5000 (옵션 · default 600)
 *
 * ## output
 * `{ code, interval, candles: [{ts, open, high, low, close, volume, ...}], hasMore, nextBefore }`
 */
@Component
class GetCandlesTool(
    private val candleQueryService: CandleQueryService,
) : McpTool {
    override val name: String = "get_candles"
    override val description: String =
        "한국 주식 OHLCV 캔들 시계열 조회 (adjusted 가격 기본). DB candle + Redis current/today 합류."

    override val inputSchema: Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "code" to
                        mapOf(
                            "type" to "string",
                            "pattern" to "^[A-Z0-9.]{1,10}$",
                            "description" to "종목/지수 코드 (예: 005930 · .IXIC)",
                        ),
                    "interval" to
                        mapOf(
                            "type" to "string",
                            "enum" to candleQueryService.supportedIntervals.sorted(),
                            "description" to "캔들 인터벌",
                        ),
                    "before" to
                        mapOf(
                            "type" to "string",
                            "format" to "date-time",
                            "description" to "페이지네이션 cursor (ISO8601 · 옵션)",
                        ),
                    "limit" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to 5000,
                            "default" to 600,
                            "description" to "응답 캔들 수 (default 600 · max 5000)",
                        ),
                ),
            "required" to listOf("code", "interval"),
        )

    override fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?> {
        val code = params.requireString("code") { "code는 6자리 종목코드 또는 지수 코드" }
        val interval =
            params
                .requireEnum("interval", candleQueryService.supportedIntervals)
                .takeIf { it in candleQueryService.supportedIntervals }
                ?: throw McpToolException("interval 검증 실패")
        val before = params.optionalString("before")
        val limit =
            params.optionalInt("limit", default = candleQueryService.defaultLimit, min = 1, max = candleQueryService.maxLimit)
                ?: candleQueryService.defaultLimit

        val response = candleQueryService.getCandles(code, interval, before, limit)
        return mapOf(
            "code" to response.code,
            "interval" to response.interval,
            "candles" to response.candles.map { it.toMap() },
            "hasMore" to response.hasMore,
            "nextBefore" to response.nextBefore,
        )
    }

    private fun CandleDto.toMap(): Map<String, Any?> =
        mapOf(
            "ts" to ts,
            "open" to open,
            "high" to high,
            "low" to low,
            "close" to close,
            "volume" to volume,
            "previousClose" to previousClose,
            "change" to change,
            "changeRate" to changeRate,
        )
}
