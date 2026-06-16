package kr.spdaewoon.magicjar.api.stomp

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.stomp.StompEvent
import kr.spdaewoon.magicjar.persistence.shared.stomp.StompEventJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.stomp.StompEventJpaRepository
import kr.spdaewoon.magicjar.persistence.shared.stomp.StompEventRepositoryAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.time.Instant

/**
 * StompEventRepositoryAdapter 단위 테스트 — ADR 0084 P1-2.
 *
 * 4 케이스:
 *  (1) save — StompEvent → JPA entity 변환 후 저장
 *  (2) findSince (channel null) — userId + since로 backfill 조회 후 domain 변환
 *  (3) findSince (channel filter) — channel 필터 JPA 쿼리 분기 정합
 *  (4) deleteOlderThan — retention 적용 + 삭제 row 수 반환
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class StompEventRepositoryAdapterTest {
    private val jpaRepository = mockk<StompEventJpaRepository>(relaxed = true)
    private val adapter = StompEventRepositoryAdapter(jpaRepository)

    private fun sampleDomain(): StompEvent =
        StompEvent(
            id = null,
            userId = 12345678L,
            channel = "order",
            eventType = "FILLED",
            payloadJson = """{"event":"FILLED","orderId":1,"code":"005930"}""",
            createdAt = Instant.parse("2026-05-19T01:00:00Z"),
        )

    private fun sampleEntity(
        id: Long,
        channel: String = "order",
        eventType: String = "FILLED",
    ) = StompEventJpaEntity(
        id = id,
        userId = 12345678L,
        channel = channel,
        eventType = eventType,
        payloadJson = """{"event":"$eventType"}""",
        createdAt = Instant.parse("2026-05-19T01:00:00Z"),
    )

    @Test
    fun `(1) save — JPA entity 변환 후 저장 + domain 반환`() {
        val event = sampleDomain()
        val entity = sampleEntity(id = 1L)
        every { jpaRepository.save(any<StompEventJpaEntity>()) } returns entity

        val result = adapter.save(event)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.userId).isEqualTo(12345678L)
        assertThat(result.channel).isEqualTo("order")
        assertThat(result.eventType).isEqualTo("FILLED")
        val captured = slot<StompEventJpaEntity>()
        verify { jpaRepository.save(capture(captured)) }
        assertThat(captured.captured.userId).isEqualTo(12345678L)
        assertThat(captured.captured.channel).isEqualTo("order")
        assertThat(captured.captured.payloadJson).contains("FILLED")
    }

    @Test
    fun `(2) findSince — channel null 시 user_id + since 인덱스 path 사용`() {
        val entities = listOf(sampleEntity(2L), sampleEntity(1L))
        every {
            jpaRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(12345678L),
                any(),
                any<Pageable>(),
            )
        } returns entities

        val result =
            adapter.findSince(
                userId = 12345678L,
                since = Instant.parse("2026-05-19T00:00:00Z"),
                channel = null,
                limit = 100,
            )

        assertThat(result).hasSize(2)
        assertThat(result.all { it.userId == 12345678L }).isTrue()
        verify(exactly = 0) {
            jpaRepository.findByUserIdAndChannelAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any(), any(), any(), any())
        }
    }

    @Test
    fun `(3) findSince — channel filter 분기 + 빈 channel 은 null 취급`() {
        val entities = listOf(sampleEntity(1L, channel = "risk", eventType = "BLOCKED"))
        every {
            jpaRepository.findByUserIdAndChannelAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(12345678L),
                eq("risk"),
                any(),
                any<Pageable>(),
            )
        } returns entities

        val result =
            adapter.findSince(
                userId = 12345678L,
                since = Instant.parse("2026-05-19T00:00:00Z"),
                channel = "risk",
                limit = 50,
            )

        assertThat(result).hasSize(1)
        assertThat(result[0].channel).isEqualTo("risk")
        assertThat(result[0].eventType).isEqualTo("BLOCKED")

        // 빈 문자열 channel 은 null path 로 fallback (blank check)
        every {
            jpaRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any(), any(), any())
        } returns emptyList()
        adapter.findSince(
            userId = 12345678L,
            since = Instant.parse("2026-05-19T00:00:00Z"),
            channel = "",
            limit = 50,
        )
        verify(exactly = 1) {
            jpaRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any(), any(), any<Pageable>())
        }
    }

    @Test
    fun `(4) deleteOlderThan — retention 적용 후 삭제 row 수 반환`() {
        val cutoff = Instant.parse("2026-05-12T00:00:00Z")
        every { jpaRepository.deleteOlderThan(cutoff) } returns 42

        val deleted = adapter.deleteOlderThan(cutoff)

        assertThat(deleted).isEqualTo(42)
        verify { jpaRepository.deleteOlderThan(cutoff) }
    }
}
