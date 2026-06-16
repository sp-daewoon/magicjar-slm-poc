package kr.spdaewoon.magicjar.api.mcp

/**
 * MCP tool plug-in interface — registry plug-and-play (ADR 0027 §2-2 정합).
 *
 * 7번째 tool 추가 시 [McpToolRegistry] 본체 변경 0 — 본 interface 구현 + `@Component` 등록만으로 자동 합류.
 *
 * ### read-only 원칙 (ADR 0027 §2-2)
 * 모든 tool은 **read 전용** LOCK. write tool (주문 발행 등)은 안전성 검증 후 post-MVP 별도 ADR.
 *
 * ### LLM 호출 금지 (ADR 0026 §3 정합)
 * 본 tool 내부에서 AiCommentProvider import 금지. `explain_indicator`조차 정적 catalog만 사용 — 본체 변경 risk 차단.
 *
 * ### 4 lens 정합
 * - 세심함: [JsonSchema] 필수 + 한국어 description + null safe 의무
 * - 영향도: read-only · 기존 service reuse만 (modify 0)
 * - 연결: registry plug-and-play (이름 충돌 시 부팅 fail-fast)
 * - 확장성: 7번째 tool add-only — 본체 변경 0
 */
interface McpTool {
    /** tool 식별자 (snake_case · `get_candles` 등 · MCP protocol 정합) */
    val name: String

    /** MCP client (Claude/Cursor)에 노출할 한국어 description */
    val description: String

    /**
     * JSON Schema (MCP protocol `tools/list` 응답 정합).
     *
     * `inputSchema` 필드 — properties · required · enum · pattern 등 정밀 정합 의무.
     * `outputSchema` 는 1차 비공개 (post-MVP 추가 path · ADR 0027 §2-3 amend 정합).
     */
    val inputSchema: Map<String, Any?>

    /**
     * tool 실행.
     *
     * @param params MCP client 가 전달한 input (JSON 파싱 결과 · null safe 의무)
     * @param userId 호출 사용자 id ([McpAuditService] 기록 + multi-tenant 격리)
     * @return tool 응답 (JSON serializable Map — null value 허용)
     * @throws McpToolException 입력 검증 실패 시 한국어 message · stacktrace 노출 0
     */
    fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?>
}

/**
 * MCP tool 호출 시 발생한 사용자 친화 오류.
 *
 * stacktrace 외부 노출 차단 — [McpController] 가 본 message 만 응답 body 에 echo.
 */
class McpToolException(
    message: String,
    val httpStatus: Int = 400,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
