package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.controller.CandleDto
import kr.spdaewoon.magicjar.api.controller.CandlesResponseDto
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * GetCandlesTool — input parsing + CandleQueryService reuse 검증.
 */
class GetCandlesToolTest {
    private val candleService: CandleQueryService =
        mock {
            whenever(it.supportedIntervals).thenReturn(setOf("1m", "5m", "1d"))
            whenever(it.defaultLimit).thenReturn(600)
            whenever(it.maxLimit).thenReturn(5000)
        }
    private val tool = GetCandlesTool(candleService)

    @Test
    fun `정상 input - CandleQueryService 호출 + 응답 정합`() {
        whenever(candleService.getCandles(eq("005930"), eq("1d"), eq(null), eq(600)))
            .thenReturn(
                CandlesResponseDto(
                    code = "005930",
                    interval = "1d",
                    candles =
                        listOf(
                            CandleDto(
                                ts = "2026-05-24T00:00:00Z",
                                open = "70000",
                                high = "71000",
                                low = "69500",
                                close = "70500",
                                volume = 1000000L,
                            ),
                        ),
                    hasMore = false,
                    nextBefore = null,
                ),
            )

        val response = tool.execute(mapOf("code" to "005930", "interval" to "1d"), "user-1")

        assertThat(response["code"]).isEqualTo("005930")
        assertThat(response["interval"]).isEqualTo("1d")
        assertThat(response["hasMore"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val candles = response["candles"] as List<Map<String, Any?>>
        assertThat(candles).hasSize(1)
        assertThat(candles[0]["close"]).isEqualTo("70500")
        verify(candleService).getCandles("005930", "1d", null, 600)
    }

    @Test
    fun `code 누락 - 한국어 message`() {
        assertThatThrownBy { tool.execute(mapOf("interval" to "1d"), "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("code")
    }

    @Test
    fun `잘못된 interval - 한국어 message`() {
        assertThatThrownBy { tool.execute(mapOf("code" to "005930", "interval" to "invalid"), "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("interval")
    }

    @Test
    fun `limit cap 초과 - McpToolException`() {
        assertThatThrownBy {
            tool.execute(mapOf("code" to "005930", "interval" to "1d", "limit" to 10000), "user-1")
        }.isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("이하")
    }
}
