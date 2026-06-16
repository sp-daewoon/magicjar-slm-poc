package kr.spdaewoon.magicjar.api.mcp.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.Locale

/**
 * MCP `/api/mcp/` 모든 sub-path API key 인증 필터 (ADR 0027 §2-4 정합).
 *
 * ## 정책
 * 1. path가 `/api/mcp/`로 시작하지 않으면 통과 (다른 endpoint 영향 0)
 * 2. env `MAGICJAR_MCP_API_KEY` 미설정 시 — MCP 전체 비활성 (503 Service Unavailable + 한국어)
 * 3. `X-Api-Key` header 가 env 값과 [MessageDigest.isEqual] timing-safe compare 통과 시 [McpAuthContext] attach + 통과
 * 4. 실패 시 401 한국어 (stacktrace 0 LOCK)
 *
 * ## 1차 (W4) 보안 LOCK
 * - **localhost 한정** — `X-Forwarded-For` header 가 localhost (127.0.0.1·::1) 외 값이면 403
 *   (단, `magicjar.mcp.localhost-only=false` env 시 비활성 — 사내망 prod 진입 시 OAuth2 path)
 * - 비교는 **timing-safe** — `String ==` 금지, [MessageDigest.isEqual] 사용 (key length leak 차단)
 *
 * ## 4 lens 정합
 * - 세심함: timing-safe compare · empty key 거부 · localhost-only 게이트 · 한국어 message · stacktrace 0
 * - 영향도: path prefix 체크로 다른 endpoint 0 영향 · OnboardingGuardFilter 와 별도 (인증 분리)
 * - 연결: [UserAccountJpaRepository.findFirstUserIds] 기존 reuse — userId inject path
 * - 확장성: post-MVP per-client API key map · OAuth2 토큰 발급 path (ADR 0027 §2-4 — 2차·3차)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5) // OnboardingGuardFilter (HIGHEST_PRECEDENCE + 10) 보다 먼저
class ApiKeyAuthFilter(
    private val userAccountJpaRepository: UserAccountJpaRepository,
    @Value("\${magicjar.mcp.api-key:}") private val expectedApiKey: String,
    @Value("\${magicjar.mcp.localhost-only:true}") private val localhostOnly: Boolean,
    @Value("\${magicjar.mcp.client-id:${McpAuthContext.DEFAULT_CLIENT_ID}}") private val configuredClientId: String,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(ApiKeyAuthFilter::class.java)

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = req.requestURI
        if (!path.startsWith(MCP_PATH_PREFIX)) {
            chain.doFilter(req, res)
            return
        }

        // 1. env 미설정 시 MCP 전체 비활성
        if (expectedApiKey.isBlank()) {
            log.warn("[mcp.auth] MAGICJAR_MCP_API_KEY 미설정 — MCP 비활성 (path={})", path)
            writeError(res, 503, "MCP 서버가 비활성 상태입니다 (MAGICJAR_MCP_API_KEY 미설정)")
            return
        }

        // 2. localhost 한정 검증 (1차 보안 LOCK)
        if (localhostOnly && !isLocalhost(req)) {
            log.warn(
                "[mcp.auth] localhost 외 접근 거부 — remote={} forwardedFor={}",
                req.remoteAddr,
                req.getHeader("X-Forwarded-For"),
            )
            writeError(res, 403, "MCP는 localhost 한정 (외부 client 접근 금지 — post-MVP OAuth2)")
            return
        }

        // 3. X-Api-Key timing-safe compare
        val providedKey = req.getHeader(API_KEY_HEADER) ?: ""
        if (!isApiKeyValid(providedKey, expectedApiKey)) {
            log.warn("[mcp.auth] API key 검증 실패 — path={}", path)
            writeError(res, 401, "MCP API key 인증 실패")
            return
        }

        // 4. 통과 → McpAuthContext attach
        val userId = resolveUserId()
        if (userId == null) {
            log.warn("[mcp.auth] user_account 0 row — onboarding 미완료 (path={})", path)
            writeError(res, 401, "셋업 마법사 완료 후 MCP 사용 가능합니다")
            return
        }
        req.setAttribute(
            McpAuthContext.REQUEST_ATTRIBUTE,
            McpAuthContext(userId = userId, clientId = configuredClientId),
        )
        chain.doFilter(req, res)
    }

    /**
     * timing-safe API key compare — [MessageDigest.isEqual] 사용 (key length leak 차단).
     *
     * - 빈 key 즉시 false (early return — 그러나 비교 자체는 length-independent)
     * - byte 배열 변환 UTF-8 LOCK (multibyte char safe)
     */
    private fun isApiKeyValid(
        provided: String,
        expected: String,
    ): Boolean {
        if (provided.isBlank() || expected.isBlank()) return false
        val providedBytes = provided.toByteArray(Charsets.UTF_8)
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        // 길이 다를 때도 dummy compare — timing leak 추가 방어
        if (providedBytes.size != expectedBytes.size) {
            // length mismatch — still do a constant-time compare against self to consume similar time
            MessageDigest.isEqual(providedBytes, providedBytes)
            return false
        }
        return MessageDigest.isEqual(providedBytes, expectedBytes)
    }

    /**
     * localhost 판정 — remoteAddr 또는 X-Forwarded-For 첫 hop이 loopback 인지.
     *
     * Docker / reverse proxy 환경에서 X-Forwarded-For 가 우선 (실제 client IP).
     */
    private fun isLocalhost(req: HttpServletRequest): Boolean {
        val forwarded =
            req
                .getHeader("X-Forwarded-For")
                ?.split(",")
                ?.firstOrNull()
                ?.trim()
        val effectiveAddr = forwarded?.takeIf { it.isNotBlank() } ?: req.remoteAddr ?: ""
        val normalized = effectiveAddr.lowercase(Locale.ROOT)
        return normalized in LOCALHOST_ADDRS
    }

    /**
     * userId 해결 — 1차 single-tenant 단순화 (user_account 첫 row).
     *
     * post-MVP — API key → user_id 매핑 (multi-tenant).
     */
    private fun resolveUserId(): String? =
        runCatching {
            userAccountJpaRepository.findFirstUserIds(PageRequest.of(0, 1)).firstOrNull()
        }.getOrNull()

    private fun writeError(
        res: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        res.status = status
        res.contentType = "application/json;charset=UTF-8"
        // 한국어 message — Jackson 미사용 (의도적 — filter 단계 dependency 최소화)
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        res.writer.write("""{"error":"$escaped"}""")
    }

    private companion object {
        const val MCP_PATH_PREFIX = "/api/mcp/"
        const val API_KEY_HEADER = "X-Api-Key"
        val LOCALHOST_ADDRS =
            setOf(
                "127.0.0.1",
                "0:0:0:0:0:0:0:1",
                "::1",
                "localhost",
            )
    }
}
