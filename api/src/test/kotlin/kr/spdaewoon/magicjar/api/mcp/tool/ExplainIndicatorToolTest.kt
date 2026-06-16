package kr.spdaewoon.magicjar.api.mcp.tool

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ExplainIndicatorTool — 정적 catalog 응답 검증 + LLM 호출 0 LOCK.
 *
 * 4 lens 영향도 — AiCommentProvider import 0 (구조적 — 본 tool 의존성 0건).
 */
class ExplainIndicatorToolTest {
    private val tool = ExplainIndicatorTool(ObjectMapper())

    @Test
    fun `RSI catalog 응답 정합`() {
        val response = tool.execute(mapOf("name" to "RSI"), "user-1")
        assertThat(response["name"]).isEqualTo("RSI")
        assertThat(response["fullName"]).isEqualTo("Relative Strength Index")
        assertThat(response["category"]).isEqualTo("momentum")
        assertThat(response["formula"].toString()).contains("RS")
        assertThat(response["interpretation"].toString()).contains("70")
        assertThat(response["source"].toString()).contains("LLM 호출 0")
    }

    @Test
    fun `대소문자 무관 - macd lookup 도 성공`() {
        val response = tool.execute(mapOf("name" to "macd"), "user-1")
        assertThat(response["name"]).isEqualTo("MACD")
        assertThat(response["category"]).isEqualTo("trend")
    }

    @Test
    fun `미지원 지표 - 한국어 message + 지원 list echo`() {
        assertThatThrownBy { tool.execute(mapOf("name" to "UNKNOWN_INDICATOR"), "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("지표를 찾을 수 없습니다")
            .hasMessageContaining("UNKNOWN_INDICATOR")
    }

    @Test
    fun `name 누락 - 필수 파라미터 오류`() {
        assertThatThrownBy { tool.execute(emptyMap(), "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("name")
    }

    @Test
    fun `params override - 응답에 paramsApplied echo`() {
        val response =
            tool.execute(
                mapOf("name" to "RSI", "params" to mapOf("period" to 21)),
                "user-1",
            )

        @Suppress("UNCHECKED_CAST")
        val applied = response["paramsApplied"] as Map<String, Any?>
        assertThat(applied["period"]).isEqualTo(21)
    }

    @Test
    fun `catalog 에 20개 이상 지표 + inputSchema enum 정합`() {
        assertThat(tool.inputSchema["properties"]).isNotNull
        @Suppress("UNCHECKED_CAST")
        val properties = tool.inputSchema["properties"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val nameField = properties["name"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val enum = nameField["enum"] as List<String>
        assertThat(enum.size).isGreaterThanOrEqualTo(20)
        assertThat(enum).contains("RSI", "MACD", "MA", "BBANDS", "ATR", "OBV", "VWAP")
    }
}
