package kr.spdaewoon.magicjar.api.mcp.auth

/**
 * MCP 요청 인증 컨텍스트 — [ApiKeyAuthFilter] 가 통과 시 request attribute 로 attach.
 *
 * [kr.spdaewoon.magicjar.api.mcp.McpController] 가 본 컨텍스트를 읽어 audit 의 user_id · client_id 로 echo.
 *
 * ## 1차 (W4) 정책
 * - 단일 user — `userId` 는 OnboardingGuardFilter 통과 사용자 row 1행에서 inject
 * - `clientId` 는 1차 API key 단일 → 고정 "default-client" (post-MVP per-client 분리 + OAuth2)
 */
data class McpAuthContext(
    val userId: String,
    val clientId: String,
) {
    companion object {
        /** request attribute key — [ApiKeyAuthFilter] 가 SET, [kr.spdaewoon.magicjar.api.mcp.McpController] 가 GET */
        const val REQUEST_ATTRIBUTE: String = "magicjar.mcp.auth.context"

        /** 1차 단일 client id LOCK (post-MVP per-client 분리) */
        const val DEFAULT_CLIENT_ID: String = "default-client"
    }
}
