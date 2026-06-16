package kr.spdaewoon.magicjar.api.news

import kr.spdaewoon.magicjar.api.controller.NewsItem
import kr.spdaewoon.magicjar.api.controller.SymbolNewsController
import kr.spdaewoon.magicjar.api.controller.SymbolNewsListResponse
import kr.spdaewoon.magicjar.api.service.SymbolNewsService
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
 * SymbolNewsController 슬라이스 테스트 (T-NEWS Phase 2 — ADR 0075 §5 검증).
 *
 * DB·Redis·Kafka 없이 @MockBean으로 검증.
 * OnboardingGuardFilter mock: UserAccountJpaRepository.existsAny()=true
 *
 * 검증 1: GET /api/symbols/005930/news → 200 OK
 * 검증 2: GET /api/symbols/999/news → 400 (종목코드 형식 오류)
 * 검증 3: GET /api/symbols/005930/news → 404 (종목 없음)
 * 검증 4: GET /api/symbols/005930/news?size=0 → 400 (size 범위 오류)
 * 검증 5: GET /api/symbols/005930/news → 200 빈 배열 (mock 합성 0 정합)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@WebMvcTest(controllers = [SymbolNewsController::class])
class SymbolNewsControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var symbolNewsService: SymbolNewsService

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private fun sampleNewsItem() =
        NewsItem(
            id = 1L,
            source = "NAVER",
            title = "삼성전자, 메모리 반도체 가격 인상 검토",
            bodySummary = "삼성전자가 메모리 반도체 가격 인상을 검토 중인 것으로...",
            url = "https://n.news.naver.com/article/001/0012345678",
            publishedAt = OffsetDateTime.of(2026, 5, 15, 8, 50, 0, 0, ZoneOffset.ofHours(9)),
            sentiment = null, // T-AI 사이클 — 1차 NULL 고정
        )

    private fun emptyResponse(stockCode: String = "005930") =
        SymbolNewsListResponse(
            stockCode = stockCode,
            items = emptyList(),
            page = 0,
            size = 20,
            totalElements = 0L,
            totalPages = 0,
        )

    private fun sampleResponse(stockCode: String = "005930") =
        SymbolNewsListResponse(
            stockCode = stockCode,
            items = listOf(sampleNewsItem()),
            page = 0,
            size = 20,
            totalElements = 1L,
            totalPages = 1,
        )

    /**
     * 검증 1: GET /api/symbols/005930/news → 200 OK + 뉴스 1건
     */
    @Test
    fun `getNews - 200 OK 뉴스 1건`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(
            symbolNewsService.getNews(
                any(),
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).thenReturn(sampleResponse())

        mockMvc
            .get("/api/symbols/005930/news")
            .andExpect {
                status { isOk() }
                jsonPath("$.stockCode") { value("005930") }
                jsonPath("$.totalElements") { value(1) }
                jsonPath("$.items[0].source") { value("NAVER") }
                jsonPath("$.items[0].sentiment") { value(null as String?) }
            }
    }

    /**
     * 검증 2: GET /api/symbols/999/news → 400 (종목코드 형식 오류 — 6자리 아님)
     */
    @Test
    fun `getNews - 400 종목코드 형식 오류`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/symbols/999/news")
            .andExpect {
                status { isBadRequest() }
            }
    }

    /**
     * 검증 3: GET /api/symbols/005930/news → 404 (종목 없음)
     */
    @Test
    fun `getNews - 404 종목 없음`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(
            symbolNewsService.getNews(any(), anyOrNull(), anyOrNull(), any(), any()),
        ).thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: 005930"))

        mockMvc
            .get("/api/symbols/005930/news")
            .andExpect {
                status { isNotFound() }
            }
    }

    /**
     * 검증 4: GET /api/symbols/005930/news?size=0 → 400 (size 범위 오류)
     */
    @Test
    fun `getNews - 400 size 0 범위 오류`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/symbols/005930/news?size=0")
            .andExpect {
                status { isBadRequest() }
            }
    }

    /**
     * 검증 5: 뉴스 0건 → 200 빈 배열 (mock 합성 0 — feedback_no_mock_data.md 정합)
     */
    @Test
    fun `getNews - 200 빈 배열 (Naver 키 미설정 graceful 정합)`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(
            symbolNewsService.getNews(any(), anyOrNull(), anyOrNull(), any(), any()),
        ).thenReturn(emptyResponse())

        mockMvc
            .get("/api/symbols/005930/news")
            .andExpect {
                status { isOk() }
                jsonPath("$.stockCode") { value("005930") }
                jsonPath("$.totalElements") { value(0) }
                jsonPath("$.items") { isEmpty() }
            }
    }
}
