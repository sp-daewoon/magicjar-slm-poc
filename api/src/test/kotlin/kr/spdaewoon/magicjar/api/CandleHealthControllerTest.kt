package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.controller.CandleHealthController
import kr.spdaewoon.magicjar.api.service.CandleHealthService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

/**
 * GET /api/symbols/{code}/candle-health · /api/symbols/candle-health 슬라이스 테스트.
 *
 * 검증:
 *  1. 단건 정상 → 200 + reason 1줄 노출
 *  2. 잘못된 code(6자리 아님) → 400
 *  3. 배치 codes CSV → 200 + items 순서 보존
 *  4. 배치 codes 누락 → 400
 *  5. 배치 50종목 초과 → 400
 *  6. 라우팅 — /candle-health 리터럴이 /{code} path variable 보다 우선 매칭
 *
 * OnboardingGuardFilter: @MockBean UserAccountJpaRepository.existsAny()=true.
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@WebMvcTest(controllers = [CandleHealthController::class])
class CandleHealthControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var candleHealthService: CandleHealthService

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private fun health(
        code: String,
        reason: CandleHealthService.CandleHealthReason,
        status: CandleHealthService.CandleHealthStatus = CandleHealthService.CandleHealthStatus.STALE,
    ) = CandleHealthService.CandleHealth(
        code = code,
        interval = "1m",
        lastCandleAt = Instant.parse("2026-06-05T00:41:00Z"),
        staleSeconds = 7200L,
        status = status,
        reason = reason,
        reasonDetail = "테스트 사유 1줄",
        checkedAt = Instant.parse("2026-06-05T02:41:00Z"),
    )

    @Test
    fun `단건 진단 200`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleHealthService.assess(eq("005930"), any())).thenReturn(
            health("005930", CandleHealthService.CandleHealthReason.WS_NOT_SUBSCRIBED),
        )

        mockMvc.get("/api/symbols/005930/candle-health").andExpect {
            status { isOk() }
            jsonPath("$.code") { value("005930") }
            jsonPath("$.status") { value("STALE") }
            jsonPath("$.reason") { value("WS_NOT_SUBSCRIBED") }
            jsonPath("$.reasonDetail") { value("테스트 사유 1줄") }
            jsonPath("$.staleSeconds") { value(7200) }
        }
    }

    @Test
    fun `단건 진단 잘못된 code 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc.get("/api/symbols/1234/candle-health").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `배치 진단 200 — items 순서 보존`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleHealthService.assessAll(eq(listOf("005930", "000660")), any())).thenReturn(
            listOf(
                health("005930", CandleHealthService.CandleHealthReason.MARKET_CLOSED),
                health("000660", CandleHealthService.CandleHealthReason.NO_TICK),
            ),
        )

        mockMvc.get("/api/symbols/candle-health?codes=005930,000660").andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].code") { value("005930") }
            jsonPath("$.items[0].reason") { value("MARKET_CLOSED") }
            jsonPath("$.items[1].code") { value("000660") }
            jsonPath("$.items[1].reason") { value("NO_TICK") }
        }
    }

    @Test
    fun `배치 진단 codes 누락 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc.get("/api/symbols/candle-health?codes=").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `배치 진단 50종목 초과 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        val codes = (1..51).joinToString(",") { "%06d".format(it) }

        mockMvc.get("/api/symbols/candle-health?codes=$codes").andExpect {
            status { isBadRequest() }
        }
    }
}
