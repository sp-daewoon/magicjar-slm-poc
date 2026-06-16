package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/**
 * KillSwitchAutoToggler 단위 테스트 (ADR 0070 §2-11 step 5·6 자본 보호 흐름).
 *
 * ### 기본 케이스
 *  (a) drift listen → KillSwitchTogglePort.engage 호출 + killswitch.engaged.v1 publish (N-strike 도달 후)
 *  (b) idempotency — 5분 내 같은 user 중복 drift → KillSwitch 1회만 engage (다중 종목 처리)
 *  (c) Phase 2 graceful — TogglePort 부재 시 KillSwitchJpaRepository baseline reuse
 *
 * ### race-safe guard 케이스 (2026-05-21 사용자 dogfooding fix)
 *  (d) guard B (Grace Window) — 마지막 FILLED 5분 내 drift → engage 0회
 *  (e) guard C (N-Strike) — 1회 strike → engage 0회 (카운터만)
 *  (f) guard C (N-Strike) — 5분 내 2회 strike → engage 1회
 *  (g) guard B 통과 (FILLED 5분 밖) + guard C 통과 (2-strike) → engage 정상
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class KillSwitchAutoTogglerTest {
    private val eventPublisher = mockk<ReconcileEventPublisher>(relaxed = true)
    private val killSwitchPort = mockk<KillSwitchTogglePort>(relaxed = true)
    private val killSwitchJpaRepo = mockk<KillSwitchJpaRepository>(relaxed = true)
    private val portProvider = mockk<ObjectProvider<KillSwitchTogglePort>>()
    private val orderRequestRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val orderRequestRepoProvider = mockk<ObjectProvider<OrderRequestRepository>>()
    private val objectMapper = ObjectMapper()

    private fun newToggler(
        port: KillSwitchTogglePort? = killSwitchPort,
        orderRepo: OrderRequestRepository? = orderRequestRepo,
        lastFilledAt: Instant? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): KillSwitchAutoToggler {
        every { portProvider.getIfAvailable() } returns port
        every { orderRequestRepoProvider.getIfAvailable() } returns orderRepo
        if (orderRepo != null) {
            every { orderRepo.findLastFilledAt(any()) } returns lastFilledAt
        }
        val toggler =
            KillSwitchAutoToggler(
                eventPublisher = eventPublisher,
                killSwitchTogglePortProvider = portProvider,
                killSwitchJpaRepository = killSwitchJpaRepo,
                objectMapper = objectMapper,
                orderRequestRepositoryProvider = orderRequestRepoProvider,
            )
        toggler.nowSupplier = { nowMs }
        return toggler
    }

    private fun driftPayload(
        userId: String,
        symbol: String,
    ): String = """{"eventId":"x","userId":"$userId","symbol":"$symbol","kisQty":100,"internalQty":99,"qtyDiff":1}"""

    private fun consumerRecord(payload: String): ConsumerRecord<String, String> =
        ConsumerRecord(KafkaTopics.Reconcile.DRIFT, 0, 0L, "U1", payload)

    @Test
    fun `(a) drift listen — 2-strike 도달 후 KillSwitchTogglePort_engage + publish`() {
        val toggler = newToggler()
        // 같은 (U1, 005930) 2번 발생 → strike threshold 도달
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify(exactly = 1) { killSwitchPort.engage("U1", "POSITION_DRIFT") }
        verify(exactly = 1) { eventPublisher.publishKillSwitchEngaged("U1", "POSITION_DRIFT", "AUTO") }
    }

    @Test
    fun `(b) idempotency — engage 후 다중 종목 drift도 1회만 engage`() {
        val toggler = newToggler()
        // U1, 005930 2-strike → engage (1회) + window 리셋
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        // engage 직후 다른 종목 drift 2-strike — idempotency window 안이므로 engage 0회
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "000660")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "000660")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "035720")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "035720")))

        verify(exactly = 1) { killSwitchPort.engage("U1", "POSITION_DRIFT") }
        verify(exactly = 1) { eventPublisher.publishKillSwitchEngaged("U1", any(), any()) }
    }

    @Test
    fun `(c) Phase 2 graceful — TogglePort 부재 시 KillSwitchJpaRepository baseline reuse`() {
        val toggler = newToggler(port = null)
        // 2-strike 도달
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify {
            killSwitchJpaRepo.enableKillSwitch(
                userId = "U1",
                reason = "POSITION_DRIFT",
                updatedBy = "consumer",
            )
        }
        verify { eventPublisher.publishKillSwitchEngaged("U1", "POSITION_DRIFT", "AUTO") }
        assertThat(toggler).isNotNull
    }

    // ===== race-safe guard B (Grace Window) =====

    @Test
    fun `(d) guard B — 마지막 FILLED 5분 내 drift → engage 0회 (race-safe guard B)`() {
        val nowMs = System.currentTimeMillis()
        // 마지막 FILLED = 2분 전 (5분 grace window 안)
        val lastFilled = Instant.ofEpochMilli(nowMs - 2 * 60 * 1000L)
        val toggler = newToggler(lastFilledAt = lastFilled, nowMs = nowMs)

        // strike threshold 만족하는 2회 publish — 그러나 grace window가 우선
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify(exactly = 0) { killSwitchPort.engage(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishKillSwitchEngaged(any(), any(), any()) }
    }

    @Test
    fun `(g) guard B 통과 (FILLED 6분 밖) + guard C 통과 (2-strike) → engage 정상`() {
        val nowMs = System.currentTimeMillis()
        // 마지막 FILLED = 6분 전 (5분 grace window 밖)
        val lastFilled = Instant.ofEpochMilli(nowMs - 6 * 60 * 1000L)
        val toggler = newToggler(lastFilledAt = lastFilled, nowMs = nowMs)

        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify(exactly = 1) { killSwitchPort.engage("U1", "POSITION_DRIFT") }
    }

    // ===== race-safe guard C (N-Strike) =====

    @Test
    fun `(e) guard C — 1-strike만 누적 → engage 0회 (race-safe guard C)`() {
        val toggler = newToggler()
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify(exactly = 0) { killSwitchPort.engage(any(), any()) }
        verify(exactly = 0) { eventPublisher.publishKillSwitchEngaged(any(), any(), any()) }
        // strikeWindow에 1건 기록
        assertThat(toggler.strikeWindow[("U1" to "005930")]).hasSize(1)
    }

    @Test
    fun `(f) guard C — 5분 내 2-strike → engage 1회 (threshold 도달)`() {
        val nowMs = System.currentTimeMillis()
        val toggler = newToggler(nowMs = nowMs)
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        // 동일 시각 (sliding window 안) 2회차
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify(exactly = 1) { killSwitchPort.engage("U1", "POSITION_DRIFT") }
        // engage 후 동일 user strikeWindow 클리어
        assertThat(toggler.strikeWindow.keys.none { it.first == "U1" }).isTrue
    }

    @Test
    fun `(f-2) guard C — 5분 window 밖 strike는 cutoff 제거 → 신규 1건만 → engage 0회`() {
        val nowMs = System.currentTimeMillis()
        val past = nowMs - 6 * 60 * 1000L // 6분 전 → window 밖

        val toggler = newToggler(nowMs = past)
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        // 시계 진행 (실제 운영은 자연 흐름이지만 테스트 시계 주입)
        toggler.nowSupplier = { nowMs }
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify(exactly = 0) { killSwitchPort.engage(any(), any()) }
        // strike 리스트에 nowMs 1건만 (past 1건은 cutoff 제거됨)
        assertThat(toggler.strikeWindow[("U1" to "005930")]).hasSize(1)
    }
}
