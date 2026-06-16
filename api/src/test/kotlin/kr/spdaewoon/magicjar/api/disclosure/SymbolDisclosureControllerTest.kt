package kr.spdaewoon.magicjar.api.disclosure

import kr.spdaewoon.magicjar.api.controller.DisclosureItem
import kr.spdaewoon.magicjar.api.controller.SymbolDisclosureController
import kr.spdaewoon.magicjar.api.controller.SymbolDisclosureListResponse
import kr.spdaewoon.magicjar.api.service.SymbolDisclosureService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * SymbolDisclosureController 슬라이스 테스트 (T-NEWS Phase 1 — ADR 0075 §5 검증).
 *
 * DB·Redis·Kafka 없이 @MockBean으로 검증.
 * OnboardingGuardFilter mock: UserAccountJpaRepository.existsAny()=true
 *
 * 검증 1: GET /api/symbols/005930/disclosures → 200 OK
 * 검증 2: GET /api/symbols/999/disclosures → 400 (종목코드 형식 오류)
 * 검증 3: GET /api/symbols/005930/disclosures → 404 (종목 없음 — service throws)
 * 검증 4: GET /api/symbols/005930/disclosures?onlyCorrection=true → 200 (정정공시 필터)
 * 검증 5: GET /api/symbols/005930/disclosures?size=0 → 400 (size 범위 오류)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@WebMvcTest(controllers = [SymbolDisclosureController::class])
class SymbolDisclosureControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var symbolDisclosureService: SymbolDisclosureService

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private fun sampleDisclosureItem(isCorrection: Boolean = false) =
        DisclosureItem(
            id = 1L,
            rceptNo = "20260515000123",
            corpCode = "00126380",
            corpName = "삼성전자",
            reportNm = if (isCorrection) "[기재정정]사업보고서" else "사업보고서",
            flrNm = "삼성전자",
            rm = null,
            isCorrection = isCorrection,
            url = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515000123",
            rceptDt = "20260515",
            ingestedAt = OffsetDateTime.of(2026, 5, 15, 9, 31, 21, 0, ZoneOffset.ofHours(9)),
        )

    @Test
    fun `GET disclosures 200 OK`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(symbolDisclosureService.getDisclosures(any(), anyOrNull(), anyOrNull(), any(), any(), any()))
            .thenReturn(
                SymbolDisclosureListResponse(
                    stockCode = "005930",
                    items = listOf(sampleDisclosureItem()),
                    page = 0,
                    size = 20,
                    totalElements = 1L,
                    totalPages = 1,
                ),
            )

        mockMvc
            .get("/api/symbols/005930/disclosures")
            .andExpect {
                status { isOk() }
                jsonPath("$.stockCode") { value("005930") }
                jsonPath("$.items.length()") { value(1) }
                jsonPath("$.totalElements") { value(1) }
                jsonPath("$.items[0].isCorrection") { value(false) }
            }
    }

    @Test
    fun `GET disclosures 종목코드 형식 오류 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/symbols/999/disclosures") // 3자리 — 형식 오류
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET disclosures 종목 없음 404`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(symbolDisclosureService.getDisclosures(any(), anyOrNull(), anyOrNull(), any(), any(), any()))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: 000001"))

        mockMvc
            .get("/api/symbols/000001/disclosures")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET disclosures onlyCorrection=true 200 — 정정공시 필터`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(symbolDisclosureService.getDisclosures(any(), anyOrNull(), anyOrNull(), any(), any(), any()))
            .thenReturn(
                SymbolDisclosureListResponse(
                    stockCode = "005930",
                    items = listOf(sampleDisclosureItem(isCorrection = true)),
                    page = 0,
                    size = 20,
                    totalElements = 1L,
                    totalPages = 1,
                ),
            )

        mockMvc
            .get("/api/symbols/005930/disclosures?onlyCorrection=true")
            .andExpect {
                status { isOk() }
                jsonPath("$.items[0].isCorrection") { value(true) }
                jsonPath("$.items[0].reportNm") { value("[기재정정]사업보고서") }
            }
    }

    @Test
    fun `GET disclosures size=0 400 — 페이징 범위 오류`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/symbols/005930/disclosures?size=0")
            .andExpect {
                status { isBadRequest() }
            }
    }
}
