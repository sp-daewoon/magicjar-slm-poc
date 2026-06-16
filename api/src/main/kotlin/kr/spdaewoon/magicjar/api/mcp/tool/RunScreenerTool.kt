package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.controller.screener.ScreenerEvaluateRequest
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerMatchDto
import kr.spdaewoon.magicjar.api.mcp.McpTool
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalInt
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalString
import kr.spdaewoon.magicjar.api.service.screener.ScreenerService
import org.springframework.stereotype.Component

/**
 * MCP tool `run_screener` — universe DSL screening (ADR 0027 §2-2).
 *
 * 기존 [ScreenerService] reuse — modify 0 (영역 침범 0 LOCK).
 * dslYaml 직접 평가 path 는 1차 비공개 (post-MVP — 권한 검증 후) — strategyId 기반 영속 DSL 만 호출 LOCK.
 *
 * ## input
 * - `strategyId`: 저장된 strategy id (positive Long · 필수)
 * - `interval`: 평가 기준봉 (옵션 · default 1d)
 * - `limit`: 1~500 (옵션 · default 50)
 *
 * ## output
 * `{ universeSize, evaluatedCount, matchedCount, matches: [{rank, code, name, score, ...}], elapsedMs, cacheHit }`
 */
@Component
class RunScreenerTool(
    private val screenerService: ScreenerService,
) : McpTool {
    override val name: String = "run_screener"
    override val description: String =
        "저장된 strategy 기반 universe screening 실행 (DSL 평가 + 랭킹). 1차 — strategyId 기반만 (dslYaml 직접 평가는 post-MVP)."

    override val inputSchema: Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "strategyId" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "description" to "저장된 strategy id (POST /api/strategies 등록 결과)",
                        ),
                    "limit" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to 500,
                            "default" to 50,
                            "description" to "랭킹 응답 최대 수",
                        ),
                    "interval" to
                        mapOf(
                            "type" to "string",
                            "enum" to
                                listOf(
                                    "1m",
                                    "3m",
                                    "5m",
                                    "15m",
                                    "30m",
                                    "90m",
                                    "1h",
                                    "4h",
                                    "12h",
                                    "1d",
                                    "1wk",
                                    "1mo",
                                    "1y",
                                ),
                            "default" to "1d",
                            "description" to "평가 기준봉",
                        ),
                ),
            "required" to listOf("strategyId"),
        )

    override fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?> {
        val strategyId =
            params.optionalInt("strategyId", min = 1)
                ?: throw McpToolException("필수 파라미터 누락: strategyId")
        val limit = params.optionalInt("limit", default = 50, min = 1, max = 500) ?: 50
        val interval = params.optionalString("interval") ?: "1d"
        val userIdLong =
            userId.toLongOrNull()
                ?: throw McpToolException("MCP user id 정수 변환 실패: '$userId'")

        val request =
            ScreenerEvaluateRequest(
                userId = userIdLong,
                strategyId = strategyId.toLong(),
                filters = null,
                interval = interval,
                limit = limit,
            )
        val result = screenerService.evaluate(request)
        return mapOf(
            "universeSize" to result.universeSize,
            "evaluatedCount" to result.evaluatedCount,
            "matchedCount" to result.matchedCount,
            "matches" to result.matches.map { it.toMap() },
            "skippedReasons" to result.skippedReasons,
            "elapsedMs" to result.elapsedMs,
            "cacheHit" to result.cacheHit,
        )
    }

    private fun ScreenerMatchDto.toMap(): Map<String, Any?> =
        mapOf(
            "rank" to rank,
            "code" to instrumentCode,
            "name" to instrumentName,
            "score" to score.toPlainString(),
            "scoreComponents" to scoreComponents.mapValues { it.value.toPlainString() },
            "reason" to reason,
        )
}
