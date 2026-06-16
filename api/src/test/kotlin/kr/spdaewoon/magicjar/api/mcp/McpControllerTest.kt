package kr.spdaewoon.magicjar.api.mcp

import kr.spdaewoon.magicjar.api.mcp.auth.McpAuthContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.mock.web.MockHttpServletRequest

/**
 * McpController — 인증 컨텍스트 부재 401 + tool 미존재 404 + execute 성공/실패 audit insert.
 */
class McpControllerTest {
    private val auditService: McpAuditService = mock()
    private val sampleTool =
        object : McpTool {
            override val name = "sample_tool"
            override val description = "stub"
            override val inputSchema = emptyMap<String, Any?>()
            var lastParams: Map<String, Any?>? = null
            var shouldFail = false

            override fun execute(
                params: Map<String, Any?>,
                userId: String,
            ): Map<String, Any?> {
                lastParams = params
                if (shouldFail) throw McpToolException("일부러 실패 한국어", httpStatus = 400)
                return mapOf("result" to "ok", "userId" to userId)
            }
        }
    private val registry = McpToolRegistry(listOf(sampleTool))
    private val controller = McpController(registry, auditService)

    @Test
    fun `listTools - 등록 tool 응답`() {
        val response = controller.listTools()

        @Suppress("UNCHECKED_CAST")
        val tools = response["tools"] as List<Map<String, Any?>>
        assertThat(tools).hasSize(1)
        assertThat(tools[0]["name"]).isEqualTo("sample_tool")
    }

    @Test
    fun `executeTool - 인증 컨텍스트 부재 시 401 + audit 미호출`() {
        val req = MockHttpServletRequest()
        // McpAuthContext 미attach
        val response = controller.executeTool("sample_tool", emptyMap(), req)
        assertThat(response.statusCode.value()).isEqualTo(401)
        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any(), any(), any())
        verify(auditService, never()).recordError(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `executeTool - tool 미존재 404`() {
        val req = withAuth()
        val response = controller.executeTool("nonexistent", emptyMap(), req)
        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(response.body!!["error"].toString()).contains("미존재")
    }

    @Test
    fun `executeTool - 성공 시 audit recordSuccess`() {
        val req = withAuth()
        sampleTool.shouldFail = false
        val response = controller.executeTool("sample_tool", mapOf("foo" to "bar"), req)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!["result"]).isEqualTo("ok")
        assertThat(response.body!!["userId"]).isEqualTo("user-1")
        verify(auditService).recordSuccess(
            eq("user-1"),
            eq("default-client"),
            eq("sample_tool"),
            eq(mapOf("foo" to "bar")),
            any(),
            any(),
            any(),
        )
    }

    @Test
    fun `executeTool - McpToolException 시 audit recordError + status 매핑`() {
        val req = withAuth()
        sampleTool.shouldFail = true
        val response = controller.executeTool("sample_tool", emptyMap(), req)

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!["error"]).isEqualTo("일부러 실패 한국어")
        verify(auditService).recordError(
            eq("user-1"),
            eq("default-client"),
            eq("sample_tool"),
            any(),
            eq("일부러 실패 한국어"),
            any(),
            any(),
        )
    }

    private fun withAuth(): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            setAttribute(
                McpAuthContext.REQUEST_ATTRIBUTE,
                McpAuthContext(userId = "user-1", clientId = "default-client"),
            )
        }
}
