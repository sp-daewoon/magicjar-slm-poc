package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/**
 * ADR 0115 §P2-4 R2 배선 테스트 — **R3 시나리오 6 (ACK 재조회)**.
 *
 * 2026-06-08 MOCK 알람 폭주 incident 의 ACK 정체 주문(144·176)을 회귀 데이터로 고정한다.
 * WS 체결통보 누락으로 주문이 ACK/PARTIALLY_FILLED 상태에 영구 정체했을 때, 본 worker 가
 * timeout([ackStaleThresholdSeconds]) 초과 정체 주문을 발견하면 해당 (userId, mode) 묶음으로
 * [KisExecutionBackfillService.syncUserTodayExecutions] 를 발사해 KIS 당일체결 대조 → FILLED 정정을 트리거하는지 검증한다.
 *
 * R2 가 이미 넣은 23건과 **중복 금지** — StuckAckExecutionResyncWorker 는 R2 신설 컴포넌트인데 테스트 0건이었으므로
 * R3 가 이 단일 gap 을 채운다(나머지 5 시나리오는 R2 테스트 매핑표로 커버, 검증 보고 참조).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4).
 */
class StuckAckExecutionResyncWorkerTest {
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val backfill = mockk<KisExecutionBackfillService>(relaxed = true)

    private val orderRepoProvider =
        mockk<ObjectProvider<OrderRequestRepository>>(relaxed = true).also {
            every { it.getIfAvailable() } returns orderRepo
        }
    private val backfillProvider =
        mockk<ObjectProvider<KisExecutionBackfillService>>(relaxed = true).also {
            every { it.getIfAvailable() } returns backfill
        }

    private fun worker(
        enabled: Boolean = true,
        thresholdSeconds: Long = 180,
    ) = StuckAckExecutionResyncWorker(
        orderRequestRepositoryProvider = orderRepoProvider,
        executionBackfillServiceProvider = backfillProvider,
        enabled = enabled,
        ackStaleThresholdSeconds = thresholdSeconds,
    )

    @Test
    fun `incident 주문 144·176 — ACK·PARTIALLY_FILLED 정체 발견 시 syncUserTodayExecutions 발사`() {
        // 2026-06-08 incident — 주문 144(ACK), 176(PARTIALLY_FILLED) 가 WS 통보 누락으로 정체.
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(stuckOrder(id = 144L, status = OrderStatus.ACK))
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.PARTIALLY_FILLED, any(), any()) } returns
            listOf(stuckOrder(id = 176L, status = OrderStatus.PARTIALLY_FILLED))

        worker().resyncTick()

        // MOCK 모드 → AccountType.MOCK 으로 KIS 당일체결 재조회 발사(FILLED 정정 트리거).
        verify(exactly = 1) { backfill.syncUserTodayExecutions("user-008", AccountType.MOCK, any()) }
    }

    @Test
    fun `동일 (userId, mode) 묶음당 1회만 sync — 중복 KIS 호출 회피`() {
        // 같은 user-008·MOCK 정체 주문 3건 → sync 는 묶음당 1회만(rate limit 보호).
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(
                stuckOrder(id = 144L, status = OrderStatus.ACK),
                stuckOrder(id = 145L, status = OrderStatus.ACK),
            )
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.PARTIALLY_FILLED, any(), any()) } returns
            listOf(stuckOrder(id = 176L, status = OrderStatus.PARTIALLY_FILLED))

        worker().resyncTick()

        verify(exactly = 1) { backfill.syncUserTodayExecutions("user-008", AccountType.MOCK, any()) }
    }

    @Test
    fun `서로 다른 (userId, mode) — 각각 1회 sync`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.ACK, any(), any()) } returns
            listOf(
                stuckOrder(id = 144L, status = OrderStatus.ACK, userId = "user-008", mode = TradingMode.MOCK),
                stuckOrder(id = 200L, status = OrderStatus.ACK, userId = "user-009", mode = TradingMode.REAL),
            )
        every { orderRepo.findByStatusAndRequestedAtBefore(OrderStatus.PARTIALLY_FILLED, any(), any()) } returns emptyList()

        worker().resyncTick()

        verify(exactly = 1) { backfill.syncUserTodayExecutions("user-008", AccountType.MOCK, any()) }
        verify(exactly = 1) { backfill.syncUserTodayExecutions("user-009", AccountType.REAL, any()) }
    }

    @Test
    fun `정체 0건 — sync 0 (early return)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) } returns emptyList()

        worker().resyncTick()

        verify(exactly = 0) { backfill.syncUserTodayExecutions(any(), any(), any()) }
    }

    @Test
    fun `ENV off — worker no-op (정체 있어도 sync 0)`() {
        every { orderRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) } returns
            listOf(stuckOrder(id = 144L, status = OrderStatus.ACK))

        worker(enabled = false).resyncTick()

        verify(exactly = 0) { backfill.syncUserTodayExecutions(any(), any(), any()) }
    }

    @Test
    fun `backfill 서비스 미합류 — graceful skip (lookup·sync 0)`() {
        every { backfillProvider.getIfAvailable() } returns null

        worker().resyncTick()

        verify(exactly = 0) { backfill.syncUserTodayExecutions(any(), any(), any()) }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun stuckOrder(
        id: Long,
        status: OrderStatus,
        userId: String = "user-008",
        mode: TradingMode = TradingMode.MOCK,
    ): OrderRequest =
        OrderRequest(
            id = id,
            userId = userId,
            signalId = 0L,
            strategyId = 8L,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.MARKET,
            quantity = 5,
            limitPrice = null,
            status = status,
            mode = mode,
            kisOrderNo = "ODNO$id",
            kisFwdgOrgno = "00950",
            idempotencyKey = "auto-sl:$id",
            // 정체 — requestedAt 이 threshold(180s) 보다 과거(10분 전).
            requestedAt = Instant.now().minusSeconds(600),
            acknowledgedAt = Instant.now().minusSeconds(595),
        )
}
