package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.controller.market.MarketCalendarController
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarEventJpaEntity
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarEventJpaRepository
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaEntity
import kr.spdaewoon.magicjar.api.persistence.MarketCalendarJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.trend.TrendUserResolver
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
import java.time.LocalDate
import java.util.Optional

@WebMvcTest(controllers = [MarketCalendarController::class])
class MarketCalendarControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var marketCalendarJpaRepository: MarketCalendarJpaRepository

    @MockBean
    lateinit var marketCalendarEventJpaRepository: MarketCalendarEventJpaRepository

    @MockBean
    lateinit var trendUserResolver: TrendUserResolver

    @MockBean
    lateinit var userAccountJpaRepository: UserAccountJpaRepository

    private val userId = "74683223"
    private val now = Instant.parse("2026-05-25T00:00:00Z")

    private fun mockUser() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(trendUserResolver.currentUserId()).thenReturn(userId)
    }

    @Test
    fun `GET calendar - 휴장일과 사용자 이벤트를 함께 반환`() {
        mockUser()
        val date = LocalDate.parse("2026-05-25")
        whenever(marketCalendarJpaRepository.findByDateBetween(date, date)).thenReturn(
            listOf(
                MarketCalendarJpaEntity(
                    date = date,
                    isBusinessDay = false,
                    isHoliday = true,
                    holidayName = "부처님오신날",
                    source = "STATIC_SEED",
                    syncedAt = now,
                    createdAt = now,
                ),
            ),
        )
        whenever(
            marketCalendarEventJpaRepository
                .findByUserIdAndEventDateBetweenOrderByEventDateAscCreatedAtAsc(userId, date, date),
        ).thenReturn(
            listOf(
                MarketCalendarEventJpaEntity(
                    id = 1L,
                    userId = userId,
                    eventDate = date,
                    market = "KRX",
                    eventType = "IPO",
                    title = "신규 상장",
                    source = "MANUAL",
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        mockMvc
            .get("/api/market/calendar") {
                param("from", "2026-05-25")
                param("to", "2026-05-25")
            }.andExpect {
                status { isOk() }
                jsonPath("$.days[0].date") { value("2026-05-25") }
                jsonPath("$.days[0].isBusinessDay") { value(false) }
                jsonPath("$.days[0].holidayName") { value("부처님오신날") }
                jsonPath("$.days[0].events[0].eventType") { value("IPO") }
                jsonPath("$.days[0].events[0].title") { value("신규 상장") }
            }
    }

    @Test
    fun `POST event - 시장 일정 등록 201`() {
        mockUser()
        whenever(marketCalendarEventJpaRepository.save(any())).thenAnswer { invocation ->
            val entity = invocation.getArgument<MarketCalendarEventJpaEntity>(0)
            MarketCalendarEventJpaEntity(
                id = 10L,
                userId = entity.userId,
                eventDate = entity.eventDate,
                market = entity.market,
                eventType = entity.eventType,
                title = entity.title,
                description = entity.description,
                instrumentCode = entity.instrumentCode,
                source = entity.source,
                createdAt = now,
                updatedAt = now,
            )
        }

        mockMvc
            .post("/api/market/calendar/events") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "date": "2026-06-01",
                      "market": "krx",
                      "eventType": "ipo",
                      "title": " 신규 상장 ",
                      "instrumentCode": " 123456 "
                    }
                    """.trimIndent()
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { value(10) }
                jsonPath("$.market") { value("KRX") }
                jsonPath("$.eventType") { value("IPO") }
                jsonPath("$.title") { value("신규 상장") }
                jsonPath("$.instrumentCode") { value("123456") }
            }
    }

    @Test
    fun `DELETE event - 사용자 일정 삭제 204`() {
        mockUser()
        val entity =
            MarketCalendarEventJpaEntity(
                id = 1L,
                userId = userId,
                eventDate = LocalDate.parse("2026-06-01"),
                market = "KRX",
                eventType = "IPO",
                title = "신규 상장",
                createdAt = now,
                updatedAt = now,
            )
        whenever(marketCalendarEventJpaRepository.findByIdAndUserId(1L, userId))
            .thenReturn(Optional.of(entity))

        mockMvc
            .delete("/api/market/calendar/events/1")
            .andExpect {
                status { isNoContent() }
            }
    }
}
