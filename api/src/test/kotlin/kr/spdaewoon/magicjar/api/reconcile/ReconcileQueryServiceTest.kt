package kr.spdaewoon.magicjar.api.reconcile

import kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunJpaRepository
import kr.spdaewoon.magicjar.api.service.reconcile.ReconcileQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

/**
 * ReconcileQueryServiceTest — 3 케이스 (spec §2-3 ~10건 GREEN 의무 LOCK).
 *
 * listRuns 페이징 · userId 격리 · 단건 조회.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
class ReconcileQueryServiceTest {
    private val jpaRepository: ReconcileRunJpaRepository = mock(ReconcileRunJpaRepository::class.java)
    private val sut = ReconcileQueryService(jpaRepository)

    private fun makeEntity(
        id: Long = 1L,
        userId: Long = 12345678L,
        status: String = "RECONCILED",
    ): ReconcileRunJpaEntity =
        ReconcileRunJpaEntity(
            id = id,
            userId = userId,
            startedAt = Instant.parse("2026-05-14T01:00:00Z"),
            finishedAt = Instant.parse("2026-05-14T01:00:02Z"),
            driftCount = 0,
            status = status,
            lastError = null,
            triggerSource = "CRON",
        )

    @Test
    fun `listRuns — userId별 페이징 목록 반환`() {
        val entities = listOf(makeEntity(id = 3L), makeEntity(id = 2L), makeEntity(id = 1L))
        val pageable = PageRequest.of(0, 20)
        whenever(jpaRepository.findByUserIdOrderByStartedAtDesc(eq(12345678L), any()))
            .thenReturn(PageImpl(entities, pageable, 3L))

        val result = sut.listRuns(12345678L, 0, 20)

        assertThat(result.content).hasSize(3)
        assertThat(result.totalElements).isEqualTo(3L)
        assertThat(result.content[0].id).isEqualTo(3L)
    }

    @Test
    fun `findRunDetail — 단건 정상 조회 — userId 일치 시 반환`() {
        val entity = makeEntity(id = 1001L, userId = 12345678L, status = "DRIFT")
        whenever(jpaRepository.findById(1001L)).thenReturn(Optional.of(entity))

        val result = sut.findRunDetail(1001L, 12345678L)

        assertThat(result.id).isEqualTo(1001L)
        assertThat(result.userId).isEqualTo("12345678")
    }

    @Test
    fun `findRunDetail — userId 불일치 시 404 ResponseStatusException`() {
        val entity = makeEntity(id = 1001L, userId = 12345678L)
        whenever(jpaRepository.findById(1001L)).thenReturn(Optional.of(entity))

        val ex =
            assertThrows<ResponseStatusException> {
                sut.findRunDetail(1001L, 99999999L) // 다른 userId
            }

        assertThat(ex.statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `findRunDetail — 존재하지 않는 ID 시 404 ResponseStatusException`() {
        whenever(jpaRepository.findById(9999L)).thenReturn(Optional.empty())

        val ex =
            assertThrows<ResponseStatusException> {
                sut.findRunDetail(9999L, 12345678L)
            }

        assertThat(ex.statusCode.value()).isEqualTo(404)
    }
}
