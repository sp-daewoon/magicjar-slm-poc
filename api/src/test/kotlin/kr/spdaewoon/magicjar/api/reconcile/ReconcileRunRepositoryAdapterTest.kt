package kr.spdaewoon.magicjar.api.reconcile

import kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunJpaEntity
import kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.reconcile.ReconcileRunRepositoryAdapter
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.Optional

/**
 * ReconcileRunRepositoryAdapterTest — 3 케이스 (spec §2-3 ~10건 GREEN 의무 LOCK).
 *
 * save·findById·findByUserId 검증.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
class ReconcileRunRepositoryAdapterTest {
    private val jpaRepository: ReconcileRunJpaRepository = mock(ReconcileRunJpaRepository::class.java)
    private val sut = ReconcileRunRepositoryAdapter(jpaRepository)

    private fun makeEntity(
        id: Long = 1L,
        userId: Long = 12345678L,
        status: String = "RECONCILED",
        driftCount: Int = 0,
    ): ReconcileRunJpaEntity =
        ReconcileRunJpaEntity(
            id = id,
            userId = userId,
            startedAt = Instant.parse("2026-05-14T01:00:00Z"),
            finishedAt = Instant.parse("2026-05-14T01:00:02Z"),
            driftCount = driftCount,
            status = status,
            lastError = null,
            triggerSource = "CRON",
        )

    private fun makeDomain(
        id: Long? = null,
        userId: String = "12345678",
        status: ReconcileStatus = ReconcileStatus.RUNNING,
    ): ReconcileRun =
        ReconcileRun(
            id = id,
            userId = userId,
            startedAt = Instant.parse("2026-05-14T01:00:00Z"),
            status = status,
            triggerSource = ReconcileTriggerSource.CRON,
        )

    @Test
    fun `save — 도메인 ReconcileRun을 JPA 엔티티로 변환 후 저장하고 도메인 반환`() {
        val run = makeDomain(status = ReconcileStatus.RUNNING)
        val savedEntity = makeEntity(id = 100L, status = "RUNNING")
        whenever(jpaRepository.save(any<ReconcileRunJpaEntity>())).thenReturn(savedEntity)

        val result = sut.save(run)

        assertThat(result.id).isEqualTo(100L)
        assertThat(result.userId).isEqualTo("12345678")
        assertThat(result.status).isEqualTo(ReconcileStatus.RUNNING)
    }

    @Test
    fun `findById — 존재하는 ID는 도메인 ReconcileRun 반환`() {
        val entity = makeEntity(id = 1001L, status = "DRIFT", driftCount = 2)
        whenever(jpaRepository.findById(1001L)).thenReturn(Optional.of(entity))

        val result = sut.findById(1001L)

        assertThat(result).isNotNull
        assertThat(result!!.id).isEqualTo(1001L)
        assertThat(result.status).isEqualTo(ReconcileStatus.DRIFT)
        assertThat(result.driftCount).isEqualTo(2)
    }

    @Test
    fun `findById — 존재하지 않는 ID는 null 반환`() {
        whenever(jpaRepository.findById(9999L)).thenReturn(Optional.empty())

        val result = sut.findById(9999L)

        assertThat(result).isNull()
    }

    @Test
    fun `findByUserId — 페이징 목록 반환 (started_at DESC)`() {
        val entities = listOf(makeEntity(id = 2L), makeEntity(id = 1L))
        val pageable = PageRequest.of(0, 20)
        whenever(jpaRepository.findByUserIdOrderByStartedAtDesc(eq(12345678L), any()))
            .thenReturn(PageImpl(entities, pageable, 2L))

        val result = sut.findByUserId("12345678", 20, 0)

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo(2L)
        assertThat(result[1].id).isEqualTo(1L)
    }

    @Test
    fun `findByUserId — userId가 Long 변환 불가 시 emptyList 반환`() {
        val result = sut.findByUserId("not-a-number", 20, 0)
        assertThat(result).isEmpty()
    }
}
