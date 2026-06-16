package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.WatchlistAddRequest
import kr.spdaewoon.magicjar.api.controller.WatchlistEntryDto
import kr.spdaewoon.magicjar.api.service.WatchlistService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

/**
 * WatchlistController 슬라이스 테스트 (spec §H smoke 2건, 룰 4 정합).
 * DB·Redis·Kafka 없이 @MockBean으로 검증.
 *
 * 검증 1: GET /api/watchlists → 빈 배열 200 OK
 * 검증 2: POST /api/watchlists {code:"005930"} → 201 Created
 * 검증 3: DELETE /api/watchlists/005930 → 204 No Content
 * 검증 4: POST /api/watchlists {code:"ABCDEF"} → 400 (형식 위반)
 */
@WebMvcTest(
    controllers = [kr.spdaewoon.magicjar.api.controller.WatchlistController::class],
)
class WatchlistControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var watchlistService: WatchlistService

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private val userId = "74683223"

    private fun mockUserId() {
        // OnboardingGuardFilter: existsAny() = true → 셋업 완료 pass-through
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(watchlistService.currentUserId()).thenReturn(userId)
    }

    @Test
    fun `GET watchlists - 빈 목록 조회 200`() {
        mockUserId()
        whenever(watchlistService.list(userId)).thenReturn(emptyList())

        mockMvc
            .get("/api/watchlists")
            .andExpect {
                status { isOk() }
                content { json("[]") }
            }
    }

    @Test
    fun `POST watchlists - 유효 코드 등록 201`() {
        mockUserId()
        val dto =
            WatchlistEntryDto(
                id = 1L,
                userId = userId,
                code = "005930",
                priority = 0,
                createdAt = Instant.now(),
            )
        whenever(watchlistService.add(any(), any())).thenReturn(dto)

        val request = WatchlistAddRequest(code = "005930")
        mockMvc
            .post("/api/watchlists") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isCreated() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.userId") { value(userId) }
            }
    }

    @Test
    fun `POST watchlists - 형식 위반 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        val request = WatchlistAddRequest(code = "ABCDEF")
        mockMvc
            .post("/api/watchlists") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `DELETE watchlists code - 204 No Content`() {
        mockUserId()
        // remove는 Unit 반환 — 별도 stub 불필요

        mockMvc
            .delete("/api/watchlists/005930")
            .andExpect {
                status { isNoContent() }
            }
    }
}
