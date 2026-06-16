package kr.spdaewoon.magicjar.api.mcp.tool

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * BacktestStrategyTool — W4 P1 fix (2026-05-24) graceful 매핑 단위 테스트.
 *
 * 기존: McpController 의 onFailure 가 McpToolException 외 모든 ex → 500 매핑 (BacktestException 미가공 노출).
 * 신규: tool 내부에서 BacktestException → McpToolException(httpStatus=422·400·503) 변환.
 *
 * ## 시나리오
 * 1. StrategyNotFoundException → McpToolException(422 · "전략을 찾을 수 없습니다")
 * 2. 정상 BacktestResult.Success → 200 응답 path 회귀 0 (status=SUCCESS · performance · trades 정합)
 */
class BacktestStrategyToolGracefulTest {
    private val backtestService: BacktestService = mock()
    private val tool = BacktestStrategyTool(backtestService)

    private val validParams =
        mapOf(
            "strategyId" to 42,
            "universe" to listOf("005930"),
            "interval" to "1d",
            "from" to "2024-01-01",
            "to" to "2024-12-31",
        )

    @Test
    fun `StrategyNotFoundException - McpToolException 422 + 한국어 message`() {
        whenever(backtestService.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(BacktestException.StrategyNotFoundException(strategyId = 42L))

        assertThatThrownBy { tool.execute(validParams, "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .satisfies(
                java.util.function.Consumer { ex ->
                    val mcpEx = ex as McpToolException
                    assertThat(mcpEx.httpStatus).isEqualTo(422)
                    assertThat(mcpEx.message).contains("전략을 찾을 수 없습니다")
                    assertThat(mcpEx.message).contains("42")
                },
            )
    }

    @Test
    fun `InvalidRequestException - McpToolException 400 + 한국어 message`() {
        whenever(backtestService.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(BacktestException.InvalidRequestException(detail = "universe는 비어있을 수 없습니다."))

        assertThatThrownBy { tool.execute(validParams, "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .satisfies(
                java.util.function.Consumer { ex ->
                    val mcpEx = ex as McpToolException
                    assertThat(mcpEx.httpStatus).isEqualTo(400)
                    assertThat(mcpEx.message).contains("universe")
                },
            )
    }

    @Test
    fun `InsufficientCandleDataException - McpToolException 503 + 한국어 message`() {
        whenever(backtestService.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(
                BacktestException.InsufficientCandleDataException(
                    code = "005930",
                    interval = "1d",
                    from = "2024-01-01",
                    to = "2024-12-31",
                ),
            )

        assertThatThrownBy { tool.execute(validParams, "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .satisfies(
                java.util.function.Consumer { ex ->
                    val mcpEx = ex as McpToolException
                    assertThat(mcpEx.httpStatus).isEqualTo(503)
                    assertThat(mcpEx.message).contains("캔들 데이터 부족")
                },
            )
    }

    @Test
    fun `정상 BacktestResult Success - 응답 200 path 회귀 0`() {
        whenever(backtestService.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(sampleSuccess())

        val response = tool.execute(validParams, "user-1")

        assertThat(response["status"]).isEqualTo("SUCCESS")
        assertThat(response["finalCapital"]).isEqualTo("11000000")
        assertThat(response["performance"]).isInstanceOf(Map::class.java)
        assertThat(response["tradesCount"]).isEqualTo(0)
        assertThat(response["durationMs"]).isEqualTo(120L)
    }

    private fun sampleSuccess(): BacktestResult.Success =
        BacktestResult.Success(
            request = sampleRequest(),
            trades = emptyList(),
            positions = emptyList(),
            finalCapital = BigDecimal("11000000"),
            performance = samplePerformance(),
            executedAt = Instant.parse("2026-05-24T00:00:00Z"),
            durationMs = 120L,
        )

    private fun sampleRequest(): BacktestRequest =
        BacktestRequest(
            strategyId = 42L,
            universe = listOf("005930"),
            interval = "1d",
            from = LocalDate(2024, 1, 1),
            to = LocalDate(2024, 12, 31),
            initialCapital = BigDecimal("10000000"),
            priceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
            sizingPolicy = SizingPolicy.AllCapital,
        )

    private fun samplePerformance(): BacktestPerformance =
        BacktestPerformance(
            totalReturn = BigDecimal("0.10"),
            cagr = BigDecimal("0.12"),
            maxDrawdown = BigDecimal("0.05"),
            sharpeRatio = BigDecimal("1.2"),
            winRate = BigDecimal("0.55"),
            totalTrades = 10,
            winningTrades = 6,
            losingTrades = 4,
            avgWin = BigDecimal("50000"),
            avgLoss = BigDecimal("30000"),
            profitFactor = BigDecimal("1.67"),
            tradingFrequency = BigDecimal("0.04"),
        )
}
