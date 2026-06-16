package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal

@WebMvcTest(controllers = [ScenarioReplayController::class])
class ScenarioReplayControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var scenarioReplayService: ScenarioReplayService

    @MockBean
    lateinit var queryService: ScenarioReplayQueryService

    @MockBean
    lateinit var userAccountJpaRepository: UserAccountJpaRepository

    @BeforeEach
    fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    @Test
    fun `POST scenario replay run returns created replay detail`() {
        whenever(scenarioReplayService.runAndPersist(any())).thenReturn(sampleDetail())

        mockMvc
            .post("/api/scenario-replay/runs") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(sampleRequest())
            }.andExpect {
                status { isCreated() }
                jsonPath("$.run.status") { value("SUCCESS") }
                jsonPath("$.events[0].type") { value("ORDER_HELD") }
                jsonPath("$.events[0].message") { value("KIS 잔고 조회 rate limit으로 리플레이 가상 주문을 보류했습니다.") }
            }
    }

    @Test
    fun `GET scenario replay runs returns empty page`() {
        whenever(queryService.listRuns("74683223", 0, 20)).thenReturn(PageImpl(emptyList(), PageRequest.of(0, 20), 0))

        mockMvc
            .get("/api/scenario-replay/runs") {
                param("userId", "74683223")
            }.andExpect {
                status { isOk() }
                jsonPath("$.content.length()") { value(0) }
                jsonPath("$.totalElements") { value(0) }
            }
    }

    @Test
    fun `DELETE scenario replay run returns no content`() {
        mockMvc
            .delete("/api/scenario-replay/runs/1") {
                param("userId", "74683223")
            }.andExpect {
                status { isNoContent() }
            }
    }

    private fun sampleRequest(): ScenarioReplayRunRequestDto =
        ScenarioReplayRunRequestDto(
            userId = "74683223",
            strategyId = 43L,
            code = "000660",
            interval = "1m",
            from = "2026-06-02T07:15:00Z",
            to = "2026-06-02T07:20:00Z",
            initialCash = BigDecimal("1000000"),
        )

    private fun sampleDetail(): ScenarioRunDetailDto =
        ScenarioRunDetailDto(
            run =
                ScenarioRunSummaryDto(
                    id = 1L,
                    userId = "74683223",
                    strategyId = 43L,
                    strategyName = "리플레이 QA 전략",
                    code = "000660",
                    interval = "1m",
                    rangeFrom = "2026-06-02T07:15:00Z",
                    rangeTo = "2026-06-02T07:20:00Z",
                    initialCash = BigDecimal("1000000"),
                    fillModel = "NEXT_CANDLE_OPEN",
                    status = "SUCCESS",
                    eventCount = 1,
                    finalCash = BigDecimal("1000000"),
                    abortReason = null,
                    createdAt = "2026-06-03T00:00:00Z",
                ),
            events =
                listOf(
                    ScenarioEventDto(
                        id = 1L,
                        sequenceNo = 0,
                        virtualTime = "2026-06-02T07:15:00Z",
                        type = "ORDER_HELD",
                        severity = "WARN",
                        title = "가상 주문 보류",
                        message = "KIS 잔고 조회 rate limit으로 리플레이 가상 주문을 보류했습니다.",
                        strategyId = 43L,
                        strategyName = "리플레이 QA 전략",
                        code = "000660",
                        payload = mapOf("faultType" to "BALANCE_LOOKUP_RATE_LIMITED"),
                    ),
                ),
            positions = emptyList(),
        )
}
