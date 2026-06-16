package kr.spdaewoon.magicjar.api.reconcile

import kr.spdaewoon.magicjar.api.controller.reconcile.ReconcileController
import kr.spdaewoon.magicjar.api.service.reconcile.ManualTriggerResult
import kr.spdaewoon.magicjar.api.service.reconcile.ReconcileManualTriggerService
import kr.spdaewoon.magicjar.api.service.reconcile.ReconcileQueryService
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * ReconcileControllerTest — 6 케이스 (spec §2-3 ~10건 GREEN 의무 LOCK).
 *
 * manual trigger(triggered·cached) · lock busy 409 · 페이징 list · detail · 404 userId 격리.
 *
 * // AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
class ReconcileControllerTest {
    private val reconcileManualTriggerService: ReconcileManualTriggerService =
        mock(ReconcileManualTriggerService::class.java)
    private val reconcileQueryService: ReconcileQueryService = mock(ReconcileQueryService::class.java)
    private val sut = ReconcileController(reconcileManualTriggerService, reconcileQueryService)

    private fun makeRun(
        id: Long = 1001L,
        userId: String = "12345678",
        status: ReconcileStatus = ReconcileStatus.RECONCILED,
    ): ReconcileRun =
        ReconcileRun(
            id = id,
            userId = userId,
            startedAt = Instant.parse("2026-05-14T01:00:00Z"),
            finishedAt = Instant.parse("2026-05-14T01:00:02Z"),
            driftCount = 0,
            status = status,
            triggerSource = ReconcileTriggerSource.MANUAL,
        )

    @Test
    fun `triggerManualReconcile — 신규 trigger 시 202 Accepted + eventId 반환`() {
        val eventId = "550e8400-e29b-41d4-a716-446655440000"
        whenever(reconcileManualTriggerService.trigger(12345678L))
            .thenReturn(ManualTriggerResult.Triggered(eventId))

        val response = sut.triggerManualReconcile(12345678L)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body?.triggered).isTrue()
        assertThat(response.body?.cached).isFalse()
        assertThat(response.body?.eventId).isEqualTo(eventId)
    }

    @Test
    fun `triggerManualReconcile — idempotency cache hit 시 200 OK + cached=true`() {
        val run = makeRun(id = 999L)
        whenever(reconcileManualTriggerService.trigger(12345678L))
            .thenReturn(ManualTriggerResult.Cached(run, 999L))

        val response = sut.triggerManualReconcile(12345678L)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.cached).isTrue()
        assertThat(response.body?.lastRunId).isEqualTo(999L)
    }

    @Test
    fun `triggerManualReconcile — lock busy 시 409 예외 전파`() {
        whenever(reconcileManualTriggerService.trigger(12345678L))
            .thenThrow(ResponseStatusException(HttpStatus.CONFLICT, "reconcile 실행 중"))

        val ex =
            assertThrows<ResponseStatusException> {
                sut.triggerManualReconcile(12345678L)
            }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `listRuns — 페이징 목록 반환`() {
        val runs = listOf(makeRun(id = 3L), makeRun(id = 2L))
        val page = PageImpl(runs, PageRequest.of(0, 20), 2L)
        whenever(reconcileQueryService.listRuns(12345678L, 0, 20)).thenReturn(page)

        val response = sut.listRuns(12345678L, 0, 20)

        assertThat(response.content).hasSize(2)
        assertThat(response.totalElements).isEqualTo(2L)
    }

    @Test
    fun `findDetail — 단건 정상 반환`() {
        val run = makeRun(id = 1001L, status = ReconcileStatus.DRIFT)
        whenever(reconcileQueryService.findRunDetail(1001L, 12345678L)).thenReturn(run)

        val dto = sut.findDetail(1001L, 12345678L)

        assertThat(dto.id).isEqualTo(1001L)
        assertThat(dto.status).isEqualTo("DRIFT")
    }

    @Test
    fun `findDetail — userId 불일치 시 404 예외 전파`() {
        whenever(reconcileQueryService.findRunDetail(1001L, 99999999L))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "reconcile run 없음"))

        val ex =
            assertThrows<ResponseStatusException> {
                sut.findDetail(1001L, 99999999L)
            }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
