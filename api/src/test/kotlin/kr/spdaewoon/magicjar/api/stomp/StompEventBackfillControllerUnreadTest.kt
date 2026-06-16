package kr.spdaewoon.magicjar.api.stomp

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.controller.stomp.StompEventBackfillController
import kr.spdaewoon.magicjar.domain.stomp.port.StompEventRepository
import kr.spdaewoon.magicjar.domain.stomp.port.UnreadBounds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * StompEventBackfillController `/events/unread` 단위 테스트 — ADR 0084 P1-3 (invalidate-query 2단계 패턴).
 *
 * 5 케이스:
 *  (1) count > 0 + bounds 동행 — repository 양쪽 호출 정합
 *  (2) count == 0 — bounds 호출 0건 (조기 종료 정합)
 *  (3) channel filter — 파라미터 그대로 양쪽 repository 호출에 전파
 *  (4) bounds null safety — count > 0이지만 bounds=null (race) 응답 firstTs/lastTs null
 *  (5) since 형식 오류 — 400 BAD_REQUEST
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class StompEventBackfillControllerUnreadTest {
    private val repository = mockk<StompEventRepository>(relaxed = true)
    private val controller = StompEventBackfillController(repository)

    @Test
    fun `(1) count gt 0 + bounds 동행 — repository 양쪽 호출 정합`() {
        val since = Instant.parse("2026-05-19T01:00:00Z")
        val firstTs = Instant.parse("2026-05-19T01:00:01Z")
        val lastTs = Instant.parse("2026-05-19T01:23:45Z")
        every { repository.countUnreadSince(12345678L, since, null) } returns 7L
        every {
            repository.findUnreadBoundsSince(12345678L, since, null)
        } returns UnreadBounds(firstTs = firstTs, lastTs = lastTs)

        val response =
            controller.countUnread(
                userId = 12345678L,
                since = "2026-05-19T01:00:00Z",
                channel = null,
            )

        assertThat(response.count).isEqualTo(7L)
        assertThat(response.firstTs).isEqualTo(firstTs)
        assertThat(response.lastTs).isEqualTo(lastTs)
        assertThat(response.since).isEqualTo("2026-05-19T01:00:00Z")
        assertThat(response.channel).isNull()
        verify(exactly = 1) { repository.countUnreadSince(12345678L, since, null) }
        verify(exactly = 1) { repository.findUnreadBoundsSince(12345678L, since, null) }
    }

    @Test
    fun `(2) count 0 — bounds 호출 0건 (조기 종료 정합)`() {
        val since = Instant.parse("2026-05-19T01:00:00Z")
        every { repository.countUnreadSince(12345678L, since, null) } returns 0L

        val response =
            controller.countUnread(
                userId = 12345678L,
                since = "2026-05-19T01:00:00Z",
                channel = null,
            )

        assertThat(response.count).isEqualTo(0L)
        assertThat(response.firstTs).isNull()
        assertThat(response.lastTs).isNull()
        // count==0 → bounds 조회 skip (DB 절감)
        verify(exactly = 0) { repository.findUnreadBoundsSince(any(), any(), any()) }
    }

    @Test
    fun `(3) channel filter — 양쪽 repository 호출에 그대로 전파`() {
        val since = Instant.parse("2026-05-19T01:00:00Z")
        every { repository.countUnreadSince(12345678L, since, "risk") } returns 3L
        every {
            repository.findUnreadBoundsSince(12345678L, since, "risk")
        } returns
            UnreadBounds(
                firstTs = Instant.parse("2026-05-19T01:05:00Z"),
                lastTs = Instant.parse("2026-05-19T01:10:00Z"),
            )

        val response =
            controller.countUnread(
                userId = 12345678L,
                since = "2026-05-19T01:00:00Z",
                channel = "risk",
            )

        assertThat(response.count).isEqualTo(3L)
        assertThat(response.channel).isEqualTo("risk")
        verify { repository.countUnreadSince(12345678L, since, "risk") }
        verify { repository.findUnreadBoundsSince(12345678L, since, "risk") }
    }

    @Test
    fun `(4) bounds null safety — count gt 0 이지만 bounds=null 응답 firstTs lastTs null`() {
        val since = Instant.parse("2026-05-19T01:00:00Z")
        every { repository.countUnreadSince(12345678L, since, null) } returns 5L
        // race: count 조회와 bounds 조회 사이에 retention cron 실행으로 row 삭제된 가상 시나리오
        every { repository.findUnreadBoundsSince(12345678L, since, null) } returns null

        val response =
            controller.countUnread(
                userId = 12345678L,
                since = "2026-05-19T01:00:00Z",
                channel = null,
            )

        assertThat(response.count).isEqualTo(5L)
        assertThat(response.firstTs).isNull()
        assertThat(response.lastTs).isNull()
    }

    @Test
    fun `(5) since 형식 오류 — 400 BAD_REQUEST`() {
        assertThatThrownBy {
            controller.countUnread(
                userId = 12345678L,
                since = "invalid-iso",
                channel = null,
            )
        }.isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("since 파라미터")
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
