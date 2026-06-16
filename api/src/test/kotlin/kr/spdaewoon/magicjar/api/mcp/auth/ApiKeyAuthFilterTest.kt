package kr.spdaewoon.magicjar.api.mcp.auth

import jakarta.servlet.FilterChain
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * ApiKeyAuthFilter — timing-safe API key compare + localhost 한정 + env 미설정 graceful refuse.
 *
 * 4 lens 세심함 — timing-safe (MessageDigest.isEqual) · empty key 거부 · localhost-only 게이트 · 한국어 message.
 */
class ApiKeyAuthFilterTest {
    private val userRepo: UserAccountJpaRepository =
        mock {
            whenever(it.findFirstUserIds(any<Pageable>())).thenReturn(listOf("user-1"))
        }

    @Test
    fun `정확한 API key 통과 + McpAuthContext attach`() {
        val filter = newFilter(expected = "valid-secret")
        val req = mockReq("/api/mcp/tools", apiKey = "valid-secret", remoteAddr = "127.0.0.1")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
        val ctx = req.getAttribute(McpAuthContext.REQUEST_ATTRIBUTE) as McpAuthContext
        assertThat(ctx.userId).isEqualTo("user-1")
        assertThat(ctx.clientId).isEqualTo(McpAuthContext.DEFAULT_CLIENT_ID)
        assertThat(res.status).isEqualTo(200)
    }

    @Test
    fun `잘못된 API key 401 한국어`() {
        val filter = newFilter(expected = "valid-secret")
        val req = mockReq("/api/mcp/tools", apiKey = "wrong-secret", remoteAddr = "127.0.0.1")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, never()).doFilter(req, res)
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentAsString).contains("API key 인증 실패")
    }

    @Test
    fun `빈 API key 401`() {
        val filter = newFilter(expected = "valid-secret")
        val req = mockReq("/api/mcp/tools", apiKey = "", remoteAddr = "127.0.0.1")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, never()).doFilter(req, res)
        assertThat(res.status).isEqualTo(401)
    }

    @Test
    fun `env 미설정 시 503 한국어`() {
        val filter = newFilter(expected = "")
        val req = mockReq("/api/mcp/tools", apiKey = "any-key", remoteAddr = "127.0.0.1")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(503)
        assertThat(res.contentAsString).contains("MCP 서버가 비활성 상태")
    }

    @Test
    fun `localhost 외 접근 403`() {
        val filter = newFilter(expected = "valid-secret", localhostOnly = true)
        val req = mockReq("/api/mcp/tools", apiKey = "valid-secret", remoteAddr = "203.0.113.45")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(403)
        assertThat(res.contentAsString).contains("localhost 한정")
    }

    @Test
    fun `localhost-only false 시 외부 IP 도 통과`() {
        val filter = newFilter(expected = "valid-secret", localhostOnly = false)
        val req = mockReq("/api/mcp/tools", apiKey = "valid-secret", remoteAddr = "203.0.113.45")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
    }

    @Test
    fun `MCP 외 path 는 모든 검증 skip 통과`() {
        val filter = newFilter(expected = "")
        val req = mockReq("/api/symbols/005930", apiKey = null, remoteAddr = "203.0.113.45")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
        assertThat(res.status).isEqualTo(200)
    }

    @Test
    fun `X-Forwarded-For 첫 hop localhost 면 통과 + 외부면 403`() {
        val filter = newFilter(expected = "valid-secret")
        // 외부 client → docker reverse proxy(127.0.0.1) — X-Forwarded-For 첫 hop = 외부
        val req = mockReq("/api/mcp/tools", apiKey = "valid-secret", remoteAddr = "127.0.0.1")
        req.addHeader("X-Forwarded-For", "203.0.113.45, 127.0.0.1")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(403)
    }

    private fun newFilter(
        expected: String,
        localhostOnly: Boolean = true,
    ) = ApiKeyAuthFilter(
        userAccountJpaRepository = userRepo,
        expectedApiKey = expected,
        localhostOnly = localhostOnly,
        configuredClientId = McpAuthContext.DEFAULT_CLIENT_ID,
    )

    private fun mockReq(
        uri: String,
        apiKey: String?,
        remoteAddr: String,
    ): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            requestURI = uri
            this.remoteAddr = remoteAddr
            if (apiKey != null) addHeader("X-Api-Key", apiKey)
        }
}
