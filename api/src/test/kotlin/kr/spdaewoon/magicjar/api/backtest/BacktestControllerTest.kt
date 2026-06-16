package kr.spdaewoon.magicjar.api.backtest

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.api.controller.BacktestController
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunRequest
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

/**
 * BacktestController 슬라이스 테스트 (ADR 0056 §5-1 — REST 5+ 시나리오 의무).
 *
 * DB·Redis·JvmStrategyEvaluator 없이 @MockBean으로 검증.
 *
 * 검증 1: POST /api/backtest/run → 200 SUCCESS
 * 검증 2: POST /api/backtest/run → 206 ABORTED (TIMEOUT_QUOTA_EXCEEDED)
 * 검증 3: POST /api/backtest/run → 503 ABORTED (NO_CANDLE_DATA)
 * 검증 4: POST /api/backtest/run → 400 (strategyId 미존재)
 * 검증 5: POST /api/backtest/run → 400 (InvalidRequestException — universe 빈 목록)
 * 검증 6: POST /api/backtest/run → 400 (InvalidStrategyDslException)
 */
@WebMvcTest(controllers = [BacktestController::class])
class BacktestControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var backtestService: BacktestService

    // OnboardingGuardFilter mock 의무 (StrategyControllerTest 패턴 정합)
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    /** OnboardingGuardFilter: existsAny() = true → 401 우회 */
    @BeforeEach
    fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    private fun makeSuccessResult(): BacktestResult.Success {
        val request =
            BacktestRequest(
                strategyId = 1L,
                universe = listOf("005930"),
                interval = "1d",
                from = LocalDate(2024, 1, 1),
                to = LocalDate(2024, 12, 31),
                initialCapital = BigDecimal("10000000"),
                priceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
                sizingPolicy = SizingPolicy.AllCapital,
            )
        val performance =
            BacktestPerformance(
                totalReturn = BigDecimal("0.1500"),
                cagr = BigDecimal("0.1500"),
                maxDrawdown = BigDecimal("0.0500"),
                sharpeRatio = null,
                winRate = BigDecimal("0.6000"),
                totalTrades = 5,
                winningTrades = 3,
                losingTrades = 2,
                avgWin = BigDecimal("200000"),
                avgLoss = BigDecimal("100000"),
                profitFactor = BigDecimal("3.0000"),
                tradingFrequency = BigDecimal("0.0200"),
            )
        return BacktestResult.Success(
            request = request,
            trades = emptyList(),
            positions = emptyList(),
            finalCapital = BigDecimal("11500000"),
            performance = performance,
            executedAt = Clock.System.now(),
            durationMs = 1234L,
        )
    }

    private fun validRequest(): BacktestRunRequest =
        BacktestRunRequest(
            strategyId = 1L,
            universe = listOf("005930"),
            interval = "1d",
            from = "2024-01-01",
            to = "2024-12-31",
            initialCapital = BigDecimal("10000000"),
        )

    @Test
    fun `검증1_POST_backtest_run_200_SUCCESS`() {
        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenReturn(makeSuccessResult())

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest())
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUCCESS") }
                jsonPath("$.finalCapital") { value(11500000) }
                jsonPath("$.performance") { exists() }
                // ADR 0121 §2-2 — 사이징 echo. 기본 요청은 AllCapital(미지정 → 회귀 0).
                jsonPath("$.sizingPolicy.type") { value("ALL_CAPITAL") }
            }
    }

    @Test
    fun `검증1c_FixedFractional 요청 DTO → 도메인 SizingPolicy 매핑 + echo (ADR 0121 §2-A)`() {
        // FixedFractional 10%/5 요청을 보내면 controller가 도메인 SizingPolicy.FixedFractional로 매핑해 서비스에 전달.
        val fractionalResult =
            makeSuccessResult().let { base ->
                base.copy(
                    request =
                        base.request.copy(
                            sizingPolicy =
                                SizingPolicy.FixedFractional(positionPercent = BigDecimal("10.00"), maxConcurrent = 5),
                        ),
                )
            }
        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenReturn(fractionalResult)

        val req =
            validRequest().copy(
                sizingPolicy =
                    kr.spdaewoon.magicjar.api.controller.dto.SizingPolicyRequestDto(
                        positionPercent = BigDecimal("10.00"),
                        maxConcurrent = 5,
                    ),
            )

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(req)
            }.andExpect {
                status { isOk() }
                jsonPath("$.sizingPolicy.type") { value("FIXED_FRACTIONAL") }
                jsonPath("$.sizingPolicy.positionPercent") { value(10.00) }
                jsonPath("$.sizingPolicy.maxConcurrent") { value(5) }
            }

        // controller가 DTO를 도메인 FixedFractional로 매핑해 서비스로 전달했는지 검증.
        val captor = argumentCaptor<SizingPolicy>()
        verify(backtestService).runBacktest(
            strategyId = any(),
            universe = any(),
            interval = any(),
            fromStr = any(),
            toStr = any(),
            initialCapital = any(),
            taxModel = any(),
            commissionModel = any(),
            slippageModel = any(),
            priceAdjustmentMode = any(),
            sizingPolicy = captor.capture(),
        )
        val captured = captor.firstValue
        org.junit.jupiter.api.Assertions
            .assertTrue(captured is SizingPolicy.FixedFractional)
        captured as SizingPolicy.FixedFractional
        org.junit.jupiter.api.Assertions
            .assertEquals(0, BigDecimal("10.00").compareTo(captured.positionPercent))
        org.junit.jupiter.api.Assertions
            .assertEquals(5, captured.maxConcurrent)
    }

    @Test
    fun `검증2_POST_backtest_run_206_ABORTED_timeout`() {
        val abortedResult =
            BacktestResult.Aborted(
                request =
                    BacktestRequest(
                        strategyId = 1L,
                        universe = listOf("005930"),
                        interval = "1d",
                        from = LocalDate(2024, 1, 1),
                        to = LocalDate(2024, 12, 31),
                        initialCapital = BigDecimal("10000000"),
                        priceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
                        sizingPolicy = SizingPolicy.AllCapital,
                    ),
                partialTrades = emptyList(),
                reason = "TIMEOUT_QUOTA_EXCEEDED",
                timeoutCount = 50,
                executedAt = Clock.System.now(),
                durationMs = 5000L,
            )

        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenReturn(abortedResult)

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest())
            }.andExpect {
                status { isEqualTo(206) }
                jsonPath("$.status") { value("ABORTED") }
                jsonPath("$.abortReason") { value("TIMEOUT_QUOTA_EXCEEDED") }
            }
    }

    @Test
    fun `검증3_POST_backtest_run_503_NO_CANDLE_DATA`() {
        val abortedResult =
            BacktestResult.Aborted(
                request =
                    BacktestRequest(
                        strategyId = 1L,
                        universe = listOf("005930"),
                        interval = "1d",
                        from = LocalDate(2024, 1, 1),
                        to = LocalDate(2024, 12, 31),
                        initialCapital = BigDecimal("10000000"),
                        priceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
                        sizingPolicy = SizingPolicy.AllCapital,
                    ),
                partialTrades = emptyList(),
                reason = "NO_CANDLE_DATA",
                timeoutCount = 0,
                executedAt = Clock.System.now(),
                durationMs = 10L,
            )

        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenReturn(abortedResult)

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest())
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.abortReason") { value("NO_CANDLE_DATA") }
            }
    }

    @Test
    fun `검증4_POST_backtest_run_400_strategyId_미존재`() {
        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenThrow(BacktestException.StrategyNotFoundException(99L))

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest().copy(strategyId = 99L))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `검증5_POST_backtest_run_400_InvalidRequestException`() {
        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenThrow(BacktestException.InvalidRequestException("universe는 비어있을 수 없습니다."))

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest().copy(universe = emptyList()))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `검증6_POST_backtest_run_400_InvalidStrategyDslException`() {
        whenever(
            backtestService.runBacktest(
                strategyId = any(),
                universe = any(),
                interval = any(),
                fromStr = any(),
                toStr = any(),
                initialCapital = any(),
                taxModel = any(),
                commissionModel = any(),
                slippageModel = any(),
                priceAdjustmentMode = any(),
                sizingPolicy = any(),
            ),
        ).thenThrow(BacktestException.InvalidStrategyDslException(1L, "buyCondition이 없습니다."))

        mockMvc
            .post("/api/backtest/run") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest())
            }.andExpect {
                status { isBadRequest() }
            }
    }
}
