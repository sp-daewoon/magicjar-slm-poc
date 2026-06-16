package kr.spdaewoon.magicjar.api.stomp

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.controller.stomp.StompEventBackfillController
import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * StompEventBackfillController 단위 테스트 — ADR 0084 P1-2.
 *
 * 4 케이스:
 *  (1) findSince — since query 결과 매핑 + DTO echo 필드 정합
 *  (2) channel filter — channel 파라미터가 repository 호출에 그대로 전파
 *  (3) limit coerce — 1~500 범위 외 값 보정
 *  (4) since 형식 오류 — 400 BAD_REQUEST
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class StompEventBackfillControllerTest {
    private val repository = mockk<StompEventRepository>(relaxed = true)
    private val controller = StompEventBackfillController(repository)

    private fun sampleEvent(
        id: Long,
        channel: String = "order",
        eventType: String = "FILLED",
    ): StompEvent =
        StompEvent(
            id = id,
            userId = 12345678L,
            channel = channel,
            eventType = eventType,
            payloadJson = """{"event":"$eventType","orderId":$id}""",
            createdAt = Instant.parse("2026-05-19T01:00:0${id % 10}Z"),
        )

    @Test
    fun `(1) findSince — since query 결과 매핑 + echo 필드 정합`() {
        every {
            repository.findSince(
                userId = 12345678L,
                since = Instant.parse("2026-05-19T00:00:00Z"),
                channel = null,
                limit = 100,
            )
        } returns listOf(sampleEvent(2L), sampleEvent(1L))

        val response =
            controller.findSince(
                userId = 12345678L,
                since = "2026-05-19T00:00:00Z",
                channel = null,
                limit = 100,
            )

        assertThat(response.count).isEqualTo(2)
        assertThat(response.since).isEqualTo("2026-05-19T00:00:00Z")
        assertThat(response.channel).isNull()
        assertThat(response.content.map { it.id }).containsExactly(2L, 1L)
        assertThat(response.content[0].channel).isEqualTo("order")
        assertThat(response.content[0].eventType).isEqualTo("FILLED")
        assertThat(response.content[0].payloadJson).contains("orderId")
    }

    @Test
    fun `(2) channel filter — 파라미터 그대로 repository 전파`() {
        every {
            repository.findSince(
                userId = 12345678L,
                since = Instant.parse("2026-05-19T00:00:00Z"),
                channel = "risk",
                limit = 50,
            )
        } returns listOf(sampleEvent(1L, channel = "risk", eventType = "BLOCKED"))

        val response =
            controller.findSince(
                userId = 12345678L,
                since = "2026-05-19T00:00:00Z",
                channel = "risk",
                limit = 50,
            )

        assertThat(response.channel).isEqualTo("risk")
        assertThat(response.count).isEqualTo(1)
        assertThat(response.content[0].channel).isEqualTo("risk")
        verify {
            repository.findSince(
                userId = 12345678L,
                since = any(),
                channel = "risk",
                limit = 50,
            )
        }
    }

    @Test
    fun `(3) limit coerce — 0 이하는 1로, 500 초과는 500으로 보정`() {
        every { repository.findSince(any(), any(), any(), any()) } returns emptyList()

        controller.findSince(userId = 1L, since = "2026-05-19T00:00:00Z", channel = null, limit = 0)
        controller.findSince(userId = 1L, since = "2026-05-19T00:00:00Z", channel = null, limit = 9999)
        controller.findSince(userId = 1L, since = "2026-05-19T00:00:00Z", channel = null, limit = 100)

        verify { repository.findSince(1L, any(), null, 1) }
        verify { repository.findSince(1L, any(), null, 500) }
        verify { repository.findSince(1L, any(), null, 100) }
    }

    @Test
    fun `(4) since 형식 오류 — 400 BAD_REQUEST 던지기`() {
        assertThatThrownBy {
            controller.findSince(
                userId = 12345678L,
                since = "invalid-iso",
                channel = null,
                limit = 100,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("since 파라미터")
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
