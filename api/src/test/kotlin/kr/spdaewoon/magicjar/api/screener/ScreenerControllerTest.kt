package kr.spdaewoon.magicjar.api.screener

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerController
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerEvaluateRequest
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerMatchDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerResultDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreeningRunDetailDto
import kr.spdaewoon.magicjar.api.service.screener.ScreenerService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageImpl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * ScreenerController 슬라이스 테스트 (ADR 0071 §6 + spec §2-3 + 사용자 메모리 feedback_swagger_pattern).
 *
 * 케이스:
 * 1. POST /api/screener/evaluate 200 — 정상 평가 결과
 * 2. POST /api/screener/evaluate 409 — 분산락 충돌
 * 3. POST /api/screener/evaluate 400 — 파라미터 오류 (userId null)
 * 4. GET /api/screener/runs 200 — empty page (영속 OFF)
 * 5. GET /api/screener/runs/{id} 200 — 단건 상세
 * 6. GET /api/screener/runs/{id} 404 — 미존재
 */
@WebMvcTest(controllers = [ScreenerController::class])
class ScreenerControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var screenerService: ScreenerService

    @MockBean
    lateinit var daytradeDiscoverService: kr.spdaewoon.magicjar.api.service.screener.DaytradeDiscoverService

    @MockBean
    lateinit var discoveryScorecardQueryService: kr.spdaewoon.magicjar.api.service.screener.DiscoveryScorecardQueryService

    // OnboardingGuardFilter 의존성 Mock (StrategyControllerTest 패턴 정합)
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    /** OnboardingGuardFilter — existsAny()=true 로 온보딩 완료 상태 stub. */
    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    // ── 픽스처 ────────────────────────────────────────────────────────

    private fun buildResultDto(cacheHit: Boolean = false): ScreenerResultDto =
        ScreenerResultDto(
            cacheKey = "screener:result:dsl:abc:universe:xyz",
            universeSize = 2847,
            evaluatedCount = 2814,
            matchedCount = 3,
            skippedReasons = mapOf("캔들 미수집" to 33),
            matches =
                listOf(
                    ScreenerMatchDto(
                        rank = 1,
                        instrumentCode = "005930",
                        instrumentName = "삼성전자",
                        score = BigDecimal("0.854321"),
                        scoreComponents = mapOf("signal" to BigDecimal("0.4"), "volume" to BigDecimal("0.3")),
                        reason = "RSI(14) < 30",
                    ),
                ),
            elapsedMs = 4521L,
            cacheHit = cacheHit,
        )

    private fun evaluateRequestBody(
        userId: Long = 1L,
        strategyId: Long = 42L,
        interval: String = "1d",
    ): String = """{"userId":$userId,"strategyId":$strategyId,"interval":"$interval","limit":50}"""

    // ── 1. POST evaluate 200 ──────────────────────────────────────────

    @Test
    fun `POST evaluate 200 — 정상 평가 결과 반환`() {
        stubOnboarding()
        whenever(screenerService.evaluate(any())).thenReturn(buildResultDto())

        mockMvc
            .post("/api/screener/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = evaluateRequestBody(interval = "3m")
            }.andExpect {
                status { isOk() }
                jsonPath("$.matchedCount") { value(3) }
                jsonPath("$.universeSize") { value(2847) }
                jsonPath("$.cacheHit") { value(false) }
                jsonPath("$.matches[0].instrumentCode") { value("005930") }
            }

        val captor = argumentCaptor<ScreenerEvaluateRequest>()
        verify(screenerService).evaluate(captor.capture())
        assertThat(captor.firstValue.interval).isEqualTo("3m")
    }

    // ── 2. POST evaluate 409 — 분산락 충돌 ───────────────────────────

    @Test
    fun `POST evaluate 409 — 분산락 충돌`() {
        stubOnboarding()
        whenever(screenerService.evaluate(any()))
            .thenThrow(ResponseStatusException(HttpStatus.CONFLICT, "동시 평가 중 — 잠시 후 재시도"))

        mockMvc
            .post("/api/screener/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = evaluateRequestBody()
            }.andExpect {
                status { isConflict() }
            }
    }

    // ── 3. POST evaluate 400 — 파라미터 오류 ─────────────────────────

    @Test
    fun `POST evaluate 400 — userId 0 (Positive 검증 실패)`() {
        stubOnboarding()
        // userId=0 → @Positive 검증 실패 → 400
        val body = """{"userId":0,"strategyId":42,"limit":50}"""

        mockMvc
            .post("/api/screener/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    // ── 4. GET runs 200 — empty page ──────────────────────────────────

    @Test
    fun `GET runs 200 — empty page (영속 OFF)`() {
        stubOnboarding()
        whenever(screenerService.listRuns(eq(1L), eq(0), eq(20))).thenReturn(PageImpl(emptyList()))

        mockMvc.get("/api/screener/runs?userId=1&page=0&size=20").andExpect {
            status { isOk() }
            jsonPath("$.content") { isArray() }
            jsonPath("$.totalElements") { value(0) }
        }
    }

    // ── 5. GET runs/{id} 200 ──────────────────────────────────────────

    @Test
    fun `GET runs id 200 — 단건 상세 조회`() {
        stubOnboarding()
        val detail =
            ScreeningRunDetailDto(
                id = 1001L,
                userId = 1L,
                strategyId = 42L,
                universeFilterHash = "abc",
                dslHash = "def",
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                universeSize = 2847,
                evaluatedCount = 2814,
                matchedCount = 3,
                skippedCount = 33,
                status = "COMPLETED",
                lastError = null,
                triggerSource = "API",
                matches = emptyList(),
            )
        whenever(screenerService.findDetail(eq(1001L), eq(1L))).thenReturn(detail)

        mockMvc.get("/api/screener/runs/1001?userId=1").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1001) }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.matchedCount") { value(3) }
        }
    }

    // ── 6. GET runs/{id} 404 ──────────────────────────────────────────

    @Test
    fun `GET runs id 404 — 미존재 또는 userId mismatch`() {
        stubOnboarding()
        whenever(screenerService.findDetail(eq(9999L), eq(1L))).thenReturn(null)

        mockMvc.get("/api/screener/runs/9999?userId=1").andExpect {
            status { isNotFound() }
        }
    }
}
