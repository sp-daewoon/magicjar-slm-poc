package kr.spdaewoon.magicjar.api.mcp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * McpToolRegistry — plug-in pattern fail-fast 검증.
 *
 * 4 lens 확장성 — 7번째 tool 추가 시 본체 변경 0 + 이름 중복 시 부팅 fail-fast.
 */
class McpToolRegistryTest {
    @Test
    fun `이름 중복 시 부팅 fail-fast`() {
        val tool1 = stubTool("get_candles")
        val tool2 = stubTool("get_candles")
        assertThatThrownBy { McpToolRegistry(listOf(tool1, tool2)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("get_candles")
    }

    @Test
    fun `tool 0건 시 부팅 fail-fast`() {
        assertThatThrownBy { McpToolRegistry(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MCP tool 0건")
    }

    @Test
    fun `list 는 이름 정렬 반환 + find 는 미존재 시 null`() {
        val registry =
            McpToolRegistry(
                listOf(
                    stubTool("zzz_tool"),
                    stubTool("aaa_tool"),
                    stubTool("mmm_tool"),
                ),
            )
        assertThat(registry.list().map { it.name }).containsExactly("aaa_tool", "mmm_tool", "zzz_tool")
        assertThat(registry.find("aaa_tool")?.name).isEqualTo("aaa_tool")
        assertThat(registry.find("nonexistent")).isNull()
    }

    private fun stubTool(toolName: String): McpTool =
        object : McpTool {
            override val name: String = toolName
            override val description: String = "stub"
            override val inputSchema: Map<String, Any?> = emptyMap()

            override fun execute(
                params: Map<String, Any?>,
                userId: String,
            ): Map<String, Any?> = emptyMap()
        }
}
