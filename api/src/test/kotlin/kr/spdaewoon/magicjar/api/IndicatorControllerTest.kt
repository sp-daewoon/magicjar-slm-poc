package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.IndicatorController
import kr.spdaewoon.magicjar.api.controller.IndicatorResultDto
import kr.spdaewoon.magicjar.api.service.IndicatorService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.server.ResponseStatusException

/**
 * IndicatorController 슬라이스 테스트 (spec §6-3 + 룰 4 정합).
 * DB·Redis·KIS 없이 @MockBean으로 검증.
 * SymbolControllerTest 패턴 정합 — OnboardingGuardFilter mock 의무.
 *
 * 검증 1: GET /api/indicators?code=005930&name=rsi&interval=1d  → 200 OK (native source)
 * 검증 2: GET /api/indicators?code=005930&name=macd&interval=1d → 200 OK (multi-output MACD)
 * 검증 3: GET /api/indicators?code=005930&name=bb&interval=1d   → 200 OK (multi-output BB)
 * 검증 4: GET /api/indicators?code=005930&name=ma&interval=1m   → 200 OK (분봉 intra)
 * 검증 5: GET /api/indicators?code=005930&name=adx&interval=1d  → 200 OK (KIS source)
 * 검증 6: GET /api/indicators?code=005930&name=unknown_xyz&interval=1d → 400 미지원 지표
 * 검증 7: GET /api/indicators?code=999&name=rsi&interval=1d     → 400 종목코드 형식 오류
 */
@WebMvcTest(controllers = [IndicatorController::class])
class IndicatorControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var indicatorService: IndicatorService

    // OnboardingGuardFilter mock 의무 (SymbolControllerTest 패턴 정합)
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    // ── helper: mock 세팅 ──────────────────────────────────────────────────

    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    private fun makeDto(
        name: String,
        source: String,
        vararg valueKeys: String,
    ): IndicatorResultDto =
        IndicatorResultDto(
            spec = IndicatorResultDto.SpecDto(name = name, params = mapOf("period" to "14")),
            series =
                listOf(
                    IndicatorResultDto.PointDto(
                        time = "2024-01-02T00:00:00Z",
                        values = valueKeys.associateWith { "72.34" },
                    ),
                ),
            source = source,
        )

    // ── 검증 1: RSI — native source 단일 출력 ─────────────────────────────

    @Test
    fun `GET indicators rsi 1d 200 OK native source`() {
        stubOnboarding()
        whenever(
            indicatorService.calculate(
                code = eq("005930"),
                name = eq("rsi"),
                interval = eq("1d"),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenReturn(makeDto("rsi", "native", "rsi"))

        mockMvc
            .get("/api/indicators?code=005930&name=rsi&interval=1d&params=period=14")
            .andExpect {
                status { isOk() }
                jsonPath("$.spec.name") { value("rsi") }
                jsonPath("$.source") { value("native") }
                jsonPath("$.series.length()") { value(1) }
                jsonPath("$.series[0].values.rsi") { value("72.34") }
            }
    }

    // ── 검증 2: MACD — multi-output (macd·signal·histogram) ──────────────

    @Test
    fun `GET indicators macd 1d 200 OK multi-output`() {
        stubOnboarding()
        val macdDto =
            IndicatorResultDto(
                spec = IndicatorResultDto.SpecDto(name = "macd", params = mapOf("fast" to "12", "slow" to "26", "signal" to "9")),
                series =
                    listOf(
                        IndicatorResultDto.PointDto(
                            time = "2024-01-02T00:00:00Z",
                            values = mapOf("macd" to "0.12", "signal" to "0.10", "histogram" to "0.02"),
                        ),
                    ),
                source = "native",
            )
        whenever(
            indicatorService.calculate(
                code = eq("005930"),
                name = eq("macd"),
                interval = eq("1d"),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenReturn(macdDto)

        mockMvc
            .get("/api/indicators?code=005930&name=macd&interval=1d&params=fast=12,slow=26,signal=9")
            .andExpect {
                status { isOk() }
                jsonPath("$.spec.name") { value("macd") }
                jsonPath("$.source") { value("native") }
                jsonPath("$.series[0].values.macd") { value("0.12") }
                jsonPath("$.series[0].values.signal") { value("0.10") }
                jsonPath("$.series[0].values.histogram") { value("0.02") }
            }
    }

    // ── 검증 3: BB — multi-output (upper·middle·lower) ────────────────────

    @Test
    fun `GET indicators bb 1d 200 OK multi-output`() {
        stubOnboarding()
        val bbDto =
            IndicatorResultDto(
                spec = IndicatorResultDto.SpecDto(name = "bb", params = mapOf("period" to "20", "std" to "2")),
                series =
                    listOf(
                        IndicatorResultDto.PointDto(
                            time = "2024-01-02T00:00:00Z",
                            values = mapOf("upper" to "63000.00", "middle" to "61000.00", "lower" to "59000.00"),
                        ),
                    ),
                source = "native",
            )
        whenever(
            indicatorService.calculate(
                code = eq("005930"),
                name = eq("bb"),
                interval = eq("1d"),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenReturn(bbDto)

        mockMvc
            .get("/api/indicators?code=005930&name=bb&interval=1d&params=period=20,std=2")
            .andExpect {
                status { isOk() }
                jsonPath("$.spec.name") { value("bb") }
                jsonPath("$.series[0].values.upper") { value("63000.00") }
                jsonPath("$.series[0].values.middle") { value("61000.00") }
                jsonPath("$.series[0].values.lower") { value("59000.00") }
            }
    }

    // ── 검증 4: MA — 분봉 1m (intra TTL 5분) ─────────────────────────────

    @Test
    fun `GET indicators ma 1m 200 OK intra interval`() {
        stubOnboarding()
        whenever(
            indicatorService.calculate(
                code = eq("005930"),
                name = eq("ma"),
                interval = eq("1m"),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenReturn(makeDto("ma", "native", "ma"))

        mockMvc
            .get("/api/indicators?code=005930&name=ma&interval=1m&params=period=20")
            .andExpect {
                status { isOk() }
                jsonPath("$.spec.name") { value("ma") }
                jsonPath("$.source") { value("native") }
            }
    }

    // ── 검증 5: ADX — KIS strategy-builder source ─────────────────────────

    @Test
    fun `GET indicators adx 1d 200 OK kis source`() {
        stubOnboarding()
        whenever(
            indicatorService.calculate(
                code = eq("005930"),
                name = eq("adx"),
                interval = eq("1d"),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenReturn(makeDto("adx", "kis-strategy-builder", "adx"))

        mockMvc
            .get("/api/indicators?code=005930&name=adx&interval=1d&params=period=14")
            .andExpect {
                status { isOk() }
                jsonPath("$.spec.name") { value("adx") }
                jsonPath("$.source") { value("kis-strategy-builder") }
            }
    }

    // ── 검증 6: 미지원 지표 → 400 ─────────────────────────────────────────

    @Test
    fun `GET indicators 미지원 지표 400`() {
        stubOnboarding()
        whenever(
            indicatorService.calculate(
                code = eq("005930"),
                name = eq("unknown_xyz"),
                interval = eq("1d"),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "No source supports unknown_xyz"))

        mockMvc
            .get("/api/indicators?code=005930&name=unknown_xyz&interval=1d")
            .andExpect {
                status { isBadRequest() }
            }
    }

    // ── 검증 7: 종목코드 형식 오류 → 400 ─────────────────────────────────

    @Test
    fun `GET indicators 종목코드 형식 오류 400`() {
        stubOnboarding()
        whenever(
            indicatorService.calculate(
                code = eq("999"),
                name = any(),
                interval = any(),
                paramsRaw = anyOrNull(),
                from = anyOrNull(),
                to = anyOrNull(),
                adjusted = any(),
            ),
        ).thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "종목코드는 6자리 숫자여야 합니다"))

        mockMvc
            .get("/api/indicators?code=999&name=rsi&interval=1d")
            .andExpect {
                status { isBadRequest() }
            }
    }
}
