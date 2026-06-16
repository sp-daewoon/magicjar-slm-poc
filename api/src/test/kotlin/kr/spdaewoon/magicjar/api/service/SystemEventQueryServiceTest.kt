package kr.spdaewoon.magicjar.api.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.Optional

/**
 * P2.1 — final reviewer ★★ #2 fix 검증.
 *
 * 4 filter case 매트릭스 정합 (SystemEventQueryService.findList):
 *
 * | userId | unreadOnly | items 호출                                                | totalCount 호출                    | unreadCount 호출                       |
 * |--------|------------|-----------------------------------------------------------|------------------------------------|----------------------------------------|
 * | Y      | true       | findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc        | countByUserIdAndViewedAtIsNull     | countByUserIdAndViewedAtIsNull         |
 * | Y      | false      | findByUserIdOrderByOccurredAtDesc                         | countByUserId                      | countByUserIdAndViewedAtIsNull         |
 * | N      | true       | findByViewedAtIsNullOrderByOccurredAtDesc                 | countByViewedAtIsNull              | countByViewedAtIsNull                  |
 * | N      | false      | findAllByOrderByOccurredAtDesc                            | jpa.count()                        | countByViewedAtIsNull                  |
 *
 * 기존 결함:
 * - userId null + unreadOnly=true → 전체 반환 (filter 무시)
 * - totalCount 가 항상 전체 count (filter 무시)
 * - userId null 시 unreadCount=0 (frontend dashboard 미확인 표시 정확성 손실)
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class SystemEventQueryServiceTest {
    private val jpa = mockk<SystemEventJpaRepository>(relaxed = true)
    private val sut = SystemEventQueryService(jpa)

    private fun entity(id: Long): SystemEventJpaEntity =
        SystemEventJpaEntity(
            id = id,
            category = "RECONCILE",
            severity = "WARN",
            actor = "Test",
            action = "TEST_ACTION",
            userId = "74683223",
            correlationId = null,
            detailJson = "{}",
            occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
        )

    @Test
    fun `userId 있고 unreadOnly=true — 사용자 unread 만 반환 + totalCount = unreadCount`() {
        val items = listOf(entity(1L), entity(2L))
        every {
            jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc("74683223", any())
        } returns items
        every { jpa.countByUserIdAndViewedAtIsNull("74683223") } returns 2L

        val result = sut.findList(userId = "74683223", unreadOnly = true, page = 0, size = 20)

        assertThat(result.items).hasSize(2)
        assertThat(result.totalCount).isEqualTo(2L)
        assertThat(result.unreadCount).isEqualTo(2L)
        verify(exactly = 1) {
            jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc("74683223", any<Pageable>())
        }
        verify(exactly = 0) { jpa.findAllByOrderByOccurredAtDesc(any()) }
        verify(exactly = 0) { jpa.count() }
    }

    @Test
    fun `userId 있고 unreadOnly=false — 사용자 전체 + totalCount=user_count + unreadCount=user_unread`() {
        val items = listOf(entity(1L), entity(2L), entity(3L))
        every { jpa.findByUserIdOrderByOccurredAtDesc("74683223", any()) } returns items
        every { jpa.countByUserId("74683223") } returns 10L
        every { jpa.countByUserIdAndViewedAtIsNull("74683223") } returns 3L

        val result = sut.findList(userId = "74683223", unreadOnly = false, page = 0, size = 20)

        assertThat(result.items).hasSize(3)
        assertThat(result.totalCount).isEqualTo(10L)
        assertThat(result.unreadCount).isEqualTo(3L)
        verify(exactly = 1) { jpa.findByUserIdOrderByOccurredAtDesc("74683223", any<Pageable>()) }
        verify(exactly = 1) { jpa.countByUserId("74683223") }
        verify(exactly = 1) { jpa.countByUserIdAndViewedAtIsNull("74683223") }
        verify(exactly = 0) { jpa.findAllByOrderByOccurredAtDesc(any()) }
        verify(exactly = 0) { jpa.count() }
    }

    @Test
    fun `P2-1 ★★ #2 — userId null 이고 unreadOnly=true — 전체 unread 만 + filter 무시 결함 fix`() {
        val items = listOf(entity(1L))
        every { jpa.findByViewedAtIsNullOrderByOccurredAtDesc(any()) } returns items
        every { jpa.countByViewedAtIsNull() } returns 5L

        val result = sut.findList(userId = null, unreadOnly = true, page = 0, size = 20)

        assertThat(result.items).hasSize(1)
        assertThat(result.totalCount).isEqualTo(5L)
        assertThat(result.unreadCount).isEqualTo(5L)
        verify(exactly = 1) { jpa.findByViewedAtIsNullOrderByOccurredAtDesc(any<Pageable>()) }
        verify(exactly = 1) { jpa.countByViewedAtIsNull() }
        // 결함 fix 증거 — 기존엔 findAllByOrderByOccurredAtDesc 호출 + jpa.count() 호출
        verify(exactly = 0) { jpa.findAllByOrderByOccurredAtDesc(any()) }
        verify(exactly = 0) { jpa.count() }
    }

    @Test
    fun `P2-1 ★★ #2 — userId null 이고 unreadOnly=false — 전체 + totalCount=jpa_count + unreadCount=countByViewedAtIsNull`() {
        val items = listOf(entity(1L), entity(2L))
        every { jpa.findAllByOrderByOccurredAtDesc(any()) } returns items
        every { jpa.count() } returns 100L
        every { jpa.countByViewedAtIsNull() } returns 7L

        val result = sut.findList(userId = null, unreadOnly = false, page = 0, size = 20)

        assertThat(result.items).hasSize(2)
        assertThat(result.totalCount).isEqualTo(100L)
        // 결함 fix 증거 — 기존엔 userId null 시 unreadCount=0L hardcoded
        assertThat(result.unreadCount).isEqualTo(7L)
        verify(exactly = 1) { jpa.findAllByOrderByOccurredAtDesc(any<Pageable>()) }
        verify(exactly = 1) { jpa.count() }
        verify(exactly = 1) { jpa.countByViewedAtIsNull() }
    }

    // ───────────────────────────────────────────────────────────────────────
    // hotfix(2026-05-20): markResolved 시 viewed_at 동반 마킹 (사용자 mental model 정합)
    //
    // 사용자 dogfooding 결함: 알람센터에서 "해결 표시" 9건 처리(resolved_at 마킹)했는데
    //   - "미확인 9건" 카운트 그대로
    //   - "미확인만 (viewed_at IS NULL)" 필터 체크해도 9건 모두 그대로 (viewed_at NULL)
    // root cause: markResolved 가 resolved_at 만 갱신 — viewed_at 은 별개 필드
    // mental model: "해결 = 확인된 것"
    // fix: viewed_at IS NULL 일 때만 viewed_at = now 동반 마킹 (idempotent 보장)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `markResolved — viewed_at IS NULL 일 때 resolved_at + viewed_at 모두 마킹 (사용자 mental model 정합)`() {
        val before =
            SystemEventJpaEntity(
                id = 42L,
                category = "RECONCILE",
                severity = "WARN",
                actor = "Test",
                action = "TEST_ACTION",
                userId = "74683223",
                correlationId = null,
                detailJson = "{}",
                occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
                resolvedAt = null,
                viewedAt = null,
            )
        every { jpa.findById(42L) } returns Optional.of(before)
        val saved = slot<SystemEventJpaEntity>()
        every { jpa.save(capture(saved)) } answers { saved.captured }

        val result = sut.markResolved(42L)

        assertThat(result).isNotNull
        assertThat(saved.captured.resolvedAt).isNotNull
        assertThat(saved.captured.viewedAt)
            .describedAs("viewed_at NULL 이었으면 동반 마킹 의무 — 사용자 mental model 정합")
            .isNotNull
        // resolvedAt 과 viewedAt 은 동일 시각(now)으로 설정 — 동일 transaction 내 1 Instant.now() 의도
        assertThat(saved.captured.viewedAt).isEqualTo(saved.captured.resolvedAt)
        verify(exactly = 1) { jpa.save(any()) }
    }

    @Test
    fun `markResolved — viewed_at NOT NULL (이미 확인됨) 일 때 viewed_at 불변 (idempotent)`() {
        val alreadyViewedAt = Instant.parse("2026-05-20T10:00:00Z")
        val before =
            SystemEventJpaEntity(
                id = 99L,
                category = "RECONCILE",
                severity = "WARN",
                actor = "Test",
                action = "TEST_ACTION",
                userId = "74683223",
                correlationId = null,
                detailJson = "{}",
                occurredAt = Instant.parse("2026-05-20T09:00:00Z"),
                resolvedAt = null,
                viewedAt = alreadyViewedAt,
            )
        every { jpa.findById(99L) } returns Optional.of(before)
        val saved = slot<SystemEventJpaEntity>()
        every { jpa.save(capture(saved)) } answers { saved.captured }

        val result = sut.markResolved(99L)

        assertThat(result).isNotNull
        assertThat(saved.captured.resolvedAt).isNotNull
        assertThat(saved.captured.viewedAt)
            .describedAs("viewed_at 이미 NOT NULL 이면 기존 값 보존 — idempotent")
            .isEqualTo(alreadyViewedAt)
        verify(exactly = 1) { jpa.save(any()) }
    }
}
