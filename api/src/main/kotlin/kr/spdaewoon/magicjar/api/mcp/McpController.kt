package kr.spdaewoon.magicjar.api.mcp

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import kr.spdaewoon.magicjar.api.mcp.auth.McpAuthContext
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * MCP HTTP endpoint — Claude·Cursor 등 AI client 가 magicJar 도메인을 호출 (ADR 0027 §2-1 정합).
 *
 * ## endpoint
 * - `GET  /api/mcp/tools`           — 등록 tool 6종 list (MCP `tools/list` 정합 simplified)
 * - `POST /api/mcp/tools/{name}`    — tool execute (request body = JSON params Map)
 *
 * SSE / streaming endpoint 는 post-MVP (현 단계 — request/response 동기 path 만).
 *
 * ## 인증·격리
 * - [kr.spdaewoon.magicjar.api.mcp.auth.ApiKeyAuthFilter] 가 사전 인증 + [McpAuthContext] attach
 * - OnboardingGuardFilter skip (ADR 0027 §2-4 인증 분리 정책)
 *
 * ## 감사
 * 모든 execute 호출 시 [McpAuditService] INSERT (success · error 양쪽)
 */
@Tag(name = "MCP Server", description = "MCP 서버 endpoint (ADR 0027 — 우리가 server · AI client) · API key 인증")
@RestController
@RequestMapping("/api/mcp")
class McpController(
    private val toolRegistry: McpToolRegistry,
    private val auditService: McpAuditService,
) {
    private val log = LoggerFactory.getLogger(McpController::class.java)

    @Operation(summary = "MCP tool 6종 list 응답 — Claude/Cursor 자동 발견")
    @GetMapping("/tools")
    fun listTools(): Map<String, Any?> =
        mapOf(
            "tools" to
                toolRegistry.list().map {
                    mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "inputSchema" to it.inputSchema,
                    )
                },
        )

    @Operation(
        summary = "MCP tool execute (read-only 6종)",
        description = "tool name (예: get_candles · get_market_trend) + JSON params. 응답은 tool 별 정의 (audit log 자동).",
    )
    @PostMapping("/tools/{name}")
    fun executeTool(
        @Parameter(description = "tool 이름 (예: get_candles · get_market_trend)", required = true)
        @PathVariable name: String,
        @RequestBody(required = false) params: Map<String, Any?>?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val auth =
            request.getAttribute(McpAuthContext.REQUEST_ATTRIBUTE) as? McpAuthContext
                ?: return ResponseEntity.status(401).body(mapOf("error" to "MCP 인증 컨텍스트 없음"))
        val tool =
            toolRegistry.find(name)
                ?: return ResponseEntity.status(404).body(mapOf("error" to "tool 미존재: '$name' (지원=${toolRegistry.list().map { it.name }})"))

        val effectiveParams = params ?: emptyMap()
        val startedAt = Instant.now()
        val startNanos = System.nanoTime()
        return runCatching { tool.execute(effectiveParams, auth.userId) }
            .fold(
                onSuccess = { response ->
                    val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000).toInt()
                    auditService.recordSuccess(
                        userId = auth.userId,
                        clientId = auth.clientId,
                        tool = name,
                        params = effectiveParams,
                        response = response,
                        latencyMs = latencyMs,
                        calledAt = startedAt,
                    )
                    log.debug("[mcp] {} OK userId={} latencyMs={}", name, auth.userId, latencyMs)
                    ResponseEntity.ok(response)
                },
                onFailure = { ex ->
                    val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000).toInt()
                    val (status, message) =
                        when (ex) {
                            is McpToolException -> ex.httpStatus to (ex.message ?: "tool 실행 오류")
                            else -> 500 to "tool 내부 오류 (${ex::class.simpleName})"
                        }
                    auditService.recordError(
                        userId = auth.userId,
                        clientId = auth.clientId,
                        tool = name,
                        params = effectiveParams,
                        errorMessage = message,
                        latencyMs = latencyMs,
                        calledAt = startedAt,
                    )
                    log.warn("[mcp] {} FAIL userId={} status={} err={}", name, auth.userId, status, ex.message)
                    ResponseEntity.status(status).body(mapOf("error" to message))
                },
            )
    }
}
