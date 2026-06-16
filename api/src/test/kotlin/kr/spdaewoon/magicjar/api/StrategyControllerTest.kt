package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.CreateStrategyRequest
import kr.spdaewoon.magicjar.api.controller.EvaluateRequest
import kr.spdaewoon.magicjar.api.controller.StrategyController
import kr.spdaewoon.magicjar.api.controller.ValidateRequest
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

/**
 * StrategyController 슬라이스 테스트 — spec §6-4 + 룰 4 정합.
 *
 * DB·Redis·KIS 없이 @MockBean으로 검증.
 * IndicatorControllerTest 7케이스 패턴 정합.
 *
 * 검증 1: POST /api/strategy → 200 OK (전략 등록 성공 + complexity 반환)
 * 검증 2: GET /api/strategy?userId=1 → 200 OK (전략 목록)
 * 검증 3: GET /api/strategy/{id} → 200 OK (단건 조회)
 * 검증 4: GET /api/strategy/{id} → 404 (전략 없음)
 * 검증 5: DELETE /api/strategy/{id} → 204 (전략 삭제)
 * 검증 6: POST /api/strategy/validate → 200 OK (DSL 검증 성공)
 * 검증 7: POST /api/strategy → 400 (DSL 빈 값 — DslSyntaxException)
 * 검증 8: PUT /api/strategy/{id}/enable → 200 OK (재활성화)
 * 검증 9: POST /api/strategy/{id}/evaluate → StrategyEvaluatorPort 결과 반환
 */
@WebMvcTest(controllers = [StrategyController::class])
@Import(KisYamlDslParser::class)
class StrategyControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var strategyService: StrategyService

    @MockBean
    lateinit var strategyEvaluatorPort: StrategyEvaluatorPort

    @MockBean
    lateinit var candleRepository: CandleRepository

    // OnboardingGuardFilter mock 의무 (IndicatorControllerTest 패턴 정합)
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    // ── helper ────────────────────────────────────────────────────────────────

    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    private fun makeStrategy(id: Long = 1L): Strategy {
        val dsl =
            DslStrategyDefinition(
                name = "golden_cross_5_20",
                nameKo = "골든크로스 5·20",
                buyCondition = IndicatorRef("stub", emptyList()),
                sellCondition = null,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = Universe("KOSPI"),
                tf = "D1",
            )
        val complexity =
            StrategyComplexity(
                astDepth = 3,
                nodeCount = 7,
                indicatorCallCount = 2,
                estimatedEvalTimeMs = 4,
                complexityClass = ComplexityClass.LOW,
            )
        return Strategy(
            id = id,
            userId = 1L,
            definition = definition,
            complexity = complexity,
            disabledReason = StrategyDisabledReason.NULL,
            lastDisabledAt = null,
            createdAt = Instant.parse("2026-05-09T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-09T00:00:00Z"),
        )
    }

    private fun makeComplexity(): StrategyComplexity =
        StrategyComplexity(
            astDepth = 3,
            nodeCount = 7,
            indicatorCallCount = 2,
            estimatedEvalTimeMs = 4,
            complexityClass = ComplexityClass.LOW,
        )

    private fun makeCandle(
        close: String,
        ts: String,
    ): Candle {
        val price = close.toBigDecimal()
        return Candle(
            code = "005930",
            interval = CandleInterval.ONE_DAY,
            ts = KtInstant.parse(ts),
            openRaw = price,
            highRaw = price,
            lowRaw = price,
            closeRaw = price,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "TEST",
        )
    }

    // ── 검증 1: POST /api/strategy → 200 OK ─────────────────────────────────

    @Test
    fun `POST strategy 등록 성공 200`() {
        stubOnboarding()
        whenever(
            strategyService.create(
                userId = any(),
                name = any(),
                nameKo = anyOrNull(),
                entryAst = any(),
                exitAst = anyOrNull(),
                universe = any(),
                tf = any(),
                entryText = any(),
                exitText = anyOrNull(),
                codes = any(),
                intervals = any(),
                stopLossPct = anyOrNull(),
                takeProfitPct = anyOrNull(),
                scaleIn = anyOrNull(),
                partialExits = anyOrNull(),
                minHoldBars = anyOrNull(),
                viActionPolicy = anyOrNull(),
                requireCodes = any(),
            ),
        ).thenReturn(makeStrategy(1L))

        val req =
            CreateStrategyRequest(
                userId = 1L,
                name = "golden_cross_5_20",
                nameKo = "골든크로스 5·20",
                entry = "ma(5) crosses_above ma(20)",
                exit = "ma(5) crosses_below ma(20)",
            )

        mockMvc
            .post("/api/strategy") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(req)
            }.andExpect {
                status { isOk() }
                jsonPath("$.strategyId") { value(1) }
                jsonPath("$.complexity.complexityClass") { value("LOW") }
            }
    }

    // ── 검증 2: GET /api/strategy?userId=1 → 200 OK ─────────────────────────

    @Test
    fun `GET strategy 목록 200 OK`() {
        stubOnboarding()
        whenever(strategyService.findAllByUserId(eq(1L))).thenReturn(listOf(makeStrategy(1L), makeStrategy(2L)))

        mockMvc
            .get("/api/strategy?userId=1")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].id") { value(1) }
                jsonPath("$[1].id") { value(2) }
            }
    }

    // ── 검증 3: GET /api/strategy/{id} → 200 OK ─────────────────────────────

    @Test
    fun `GET strategy 단건 조회 200 OK`() {
        stubOnboarding()
        whenever(strategyService.findById(eq(1L))).thenReturn(makeStrategy(1L))

        mockMvc
            .get("/api/strategy/1")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.name") { value("golden_cross_5_20") }
                jsonPath("$.enabled") { value(true) }
            }
    }

    // ── 검증 4: GET /api/strategy/{id} → 404 ───────────────────────────────

    @Test
    fun `GET strategy 없음 404`() {
        stubOnboarding()
        whenever(strategyService.findById(eq(999L))).thenReturn(null)

        mockMvc
            .get("/api/strategy/999")
            .andExpect {
                status { isNotFound() }
            }
    }

    // ── 검증 5: DELETE /api/strategy/{id} → 204 ─────────────────────────────

    @Test
    fun `DELETE strategy 삭제 204`() {
        stubOnboarding()
        whenever(strategyService.findById(eq(1L))).thenReturn(makeStrategy(1L))

        mockMvc
            .delete("/api/strategy/1")
            .andExpect {
                status { isNoContent() }
            }
    }

    // ── 검증 6: POST /api/strategy/validate → 200 OK ─────────────────────────

    @Test
    fun `POST strategy validate 검증 성공 200`() {
        stubOnboarding()
        whenever(strategyService.validateAndComputeComplexity(any())).thenReturn(makeComplexity())

        val req = ValidateRequest(expr = "ma(5) crosses_above ma(20)")

        mockMvc
            .post("/api/strategy/validate") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(req)
            }.andExpect {
                status { isOk() }
                jsonPath("$.astDepth") { value(3) }
                jsonPath("$.nodeCount") { value(7) }
                jsonPath("$.indicatorCallCount") { value(2) }
                jsonPath("$.complexityClass") { value("LOW") }
            }
    }

    // ── 검증 7: POST /api/strategy 빈 DSL → 400 ────────────────────────────

    @Test
    fun `POST strategy 빈 DSL entry 400`() {
        stubOnboarding()

        val req =
            CreateStrategyRequest(
                userId = 1L,
                name = "invalid",
                entry = "",
            )

        mockMvc
            .post("/api/strategy") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(req)
            }.andExpect {
                status { isBadRequest() }
            }
    }

    // ── 검증 8: PUT /api/strategy/{id}/enable → 200 OK ──────────────────────

    @Test
    fun `PUT strategy enable 재활성화 200`() {
        stubOnboarding()
        whenever(strategyService.findById(eq(1L)))
            .thenReturn(makeStrategy(1L))
            .thenReturn(makeStrategy(1L))

        mockMvc
            .put("/api/strategy/1/enable")
            .andExpect {
                status { isOk() }
                jsonPath("$.enabled") { value(true) }
            }
    }

    // ── 검증 9: POST /api/strategy/{id}/evaluate → 실제 평가 포트 결과 반환 ───────

    @Test
    fun `POST strategy evaluate 실평가기 결과 반환 200`() {
        stubOnboarding()
        whenever(strategyService.findById(eq(1L))).thenReturn(makeStrategy(1L))
        whenever(candleRepository.findLatestByCodeAndInterval(eq("005930"), eq(CandleInterval.ONE_DAY), any()))
            .thenReturn(
                listOf(
                    makeCandle("70000", "2026-05-18T00:00:00Z"),
                    makeCandle("71000", "2026-05-19T00:00:00Z"),
                ),
            )
        whenever(strategyEvaluatorPort.evaluate(eq(1L), any(), any()))
            .thenReturn(
                StrategyEvalResult(
                    signal = Signal.BUY,
                    strength = BigDecimal.ONE,
                    reason = mapOf("trigger" to "TEST_BUY"),
                    evaluatedAt = KtInstant.parse("2026-05-19T00:00:01Z"),
                    evalTimeMs = 3L,
                ),
            )

        val req = EvaluateRequest(code = "005930", interval = "1d")

        mockMvc
            .post("/api/strategy/1/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(req)
            }.andExpect {
                status { isOk() }
                jsonPath("$.strategyId") { value(1) }
                jsonPath("$.signal") { value("BUY") }
                jsonPath("$.strength") { value(1) }
                jsonPath("$.reason.trigger") { value("TEST_BUY") }
                jsonPath("$.evalTimeMs") { value(3) }
            }
    }
}
