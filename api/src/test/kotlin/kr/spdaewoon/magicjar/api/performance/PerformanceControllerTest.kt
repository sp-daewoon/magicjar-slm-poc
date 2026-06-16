package kr.spdaewoon.magicjar.api.performance

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityCurveDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityPointDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceTradeBreakdownDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceTradeDto
import kr.spdaewoon.magicjar.api.controller.performance.PerformanceController
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.performance.PerformanceService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * PerformanceControllerTest — 4건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. GET /api/performance/summary 200 정상
 * 2. GET /api/performance/equity-curve 200 정상
 * 3. GET /api/performance/summary 400 invalid period
 * 4. GET /api/performance/summary 빈 데이터 (tradeCount=0)
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@WebMvcTest(controllers = [PerformanceController::class])
class PerformanceControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var performanceService: PerformanceService

    // OnboardingGuardFilter 의존성 Mock
    @MockBean
    private lateinit var userAccountJpaRepository: UserAccountJpaRepository

    private val kst = ZoneId.of("Asia/Seoul")
    private val userId = "user_test_001"

    @Test
    fun `GET summary 200 — 5 지표 정상 반환`() {
        val dto =
            PerformanceSummaryDto(
                period = "MONTH",
                periodStart = LocalDate.of(2026, 5, 1),
                periodEnd = LocalDate.of(2026, 5, 14),
                totalPnl = BigDecimal("1250000"),
                mddPct = BigDecimal("8.4321"),
                winRatePct = BigDecimal("62.50"),
                profitLossRatio = BigDecimal("1.8462"),
                tradeCount = 32,
                computedAt = OffsetDateTime.now(kst),
                cacheHit = false,
            )
        whenever(performanceService.getSummary(eq(userId), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(dto)
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/performance/summary") {
                param("userId", userId)
                param("period", "MONTH")
                accept(MediaType.APPLICATION_JSON)
            }.andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.period") { value("MONTH") }
                jsonPath("$.tradeCount") { value(32) }
                jsonPath("$.cacheHit") { value(false) }
            }
    }

    @Test
    fun `GET equity-curve 200 — points 시계열 반환`() {
        val dto =
            EquityCurveDto(
                from = LocalDate.of(2026, 4, 14),
                to = LocalDate.of(2026, 5, 14),
                points =
                    listOf(
                        EquityPointDto(LocalDate.of(2026, 5, 5), BigDecimal("100000"), BigDecimal("100000"), 1),
                        EquityPointDto(LocalDate.of(2026, 5, 8), BigDecimal("150000"), BigDecimal("250000"), 2),
                    ),
            )
        whenever(performanceService.getEquityCurve(eq(userId), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(dto)
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/performance/equity-curve") {
                param("userId", userId)
                accept(MediaType.APPLICATION_JSON)
            }.andExpect {
                status { isOk() }
                jsonPath("$.points.length()") { value(2) }
                jsonPath("$.points[0].tradeDate") { value("2026-05-05") }
            }
    }

    @Test
    fun `GET trades 200 — 실현손익 상세 반환`() {
        val dto =
            PerformanceTradeBreakdownDto(
                period = "MONTH",
                periodStart = LocalDate.of(2026, 5, 1),
                periodEnd = LocalDate.of(2026, 5, 22),
                totalRealizedPnl = BigDecimal("2166.67"),
                tradeCount = 1,
                items =
                    listOf(
                        PerformanceTradeDto(
                            executionId = 1L,
                            orderId = 6L,
                            tradeDate = LocalDate.of(2026, 5, 20),
                            executedAt = OffsetDateTime.now(kst),
                            code = "005930",
                            name = "삼성전자",
                            mode = "MOCK",
                            source = "MANUAL",
                            executedQty = BigDecimal.ONE,
                            executedPrice = BigDecimal("276000"),
                            avgBuyPrice = BigDecimal("273833.3333"),
                            fee = BigDecimal.ZERO,
                            tax = BigDecimal.ZERO,
                            realizedPnl = BigDecimal("2166.67"),
                        ),
                    ),
            )
        whenever(performanceService.getTradeBreakdown(eq(userId), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(dto)
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/performance/trades") {
                param("userId", userId)
                param("period", "MONTH")
                accept(MediaType.APPLICATION_JSON)
            }.andExpect {
                status { isOk() }
                jsonPath("$.tradeCount") { value(1) }
                jsonPath("$.items[0].code") { value("005930") }
                jsonPath("$.items[0].realizedPnl") { value(2166.67) }
            }
    }

    @Test
    fun `GET summary 400 — invalid period`() {
        whenever(performanceService.getSummary(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenThrow(ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "지원하지 않는 period"))
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/performance/summary") {
                param("userId", userId)
                param("period", "INVALID_PERIOD")
                accept(MediaType.APPLICATION_JSON)
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET summary 빈 데이터 — tradeCount=0 totalPnl=0 정상 반환`() {
        val dto =
            PerformanceSummaryDto(
                period = "MONTH",
                periodStart = LocalDate.of(2026, 5, 1),
                periodEnd = LocalDate.of(2026, 5, 14),
                totalPnl = BigDecimal.ZERO,
                mddPct = BigDecimal.ZERO,
                winRatePct = BigDecimal.ZERO,
                profitLossRatio = null,
                tradeCount = 0,
                computedAt = OffsetDateTime.now(kst),
                cacheHit = false,
            )
        whenever(performanceService.getSummary(eq(userId), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(dto)
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/performance/summary") {
                param("userId", userId)
                accept(MediaType.APPLICATION_JSON)
            }.andExpect {
                status { isOk() }
                jsonPath("$.tradeCount") { value(0) }
            }
    }
}
