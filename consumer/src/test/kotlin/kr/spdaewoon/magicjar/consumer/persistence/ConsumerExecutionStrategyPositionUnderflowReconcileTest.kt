package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * ADR 0115 §P1-1 amend (2026-06-10) — strategy_position **부족 방향** 귀속 ledger 보정 통합 테스트.
 *
 * 2026-06-10 실측 incident 재현: WS 체결통보 유실→REST backfill 흡수된 FILLED execution 이 replay-skip 으로
 * strategy_position 에 미반영(qty=0 vs KIS 3주) → reconcile 진입점이 귀속 ledger 로 +3 정정.
 * 모호 귀속 시 WARN-only, over-count 0(멱등) 검증.
 */
class ConsumerExecutionStrategyPositionUnderflowReconcileTest {
    private val port = MultiLegCapturingPort()
    private val jpaRepo: StrategyPositionJpaRepository = mock()
    private val positionRepo: PositionJpaRepository = mock()
    private val orderRequestRepo: ConsumerOrderRequestJpaRepository = mock()
    private val signalRepo: ConsumerSignalJpaRepository = mock()
    private val emittedEvents = mutableListOf<kr.spdaewoon.magicjar.domain.event.SystemEvent>()

    private val systemEventLogger =
        object : kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger {
            override fun emit(event: kr.spdaewoon.magicjar.domain.event.SystemEvent) {
                emittedEvents += event
            }
        }

    private val systemEventProvider: ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger> =
        mock<ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger>>().also {
            whenever(it.ifAvailable).thenReturn(systemEventLogger)
        }

    private val adapter =
        ConsumerExecutionStrategyPositionAdapter(
            strategyPositionPort = port,
            strategyPositionJpaRepository = jpaRepo,
            positionJpaRepository = positionRepo,
            orderRequestJpaRepository = orderRequestRepo,
            signalJpaRepository = signalRepo,
            systemEventLoggerProvider = systemEventProvider,
            driftAutoCorrectEnabled = true,
            clock = Clock.System,
        )

    @Test
    fun `incident 재현 — qty0 leg + KIS 3주 + ledger 귀속 net3 → 3주 복원 + 평단 ledger 가중평단`() {
        // strategy_position: 전략8 qty=0(reset 상태, avgPrice=0)
        port.legs = mutableListOf(strategyPosition(strategyId = 8L, quantity = 0, avgPrice = "0"))
        // KIS position 권위 = 3주
        whenever(positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK"))
            .thenReturn(positionEntity(quantity = 3))
        // ledger 귀속 — 전략8 BUY 3주 @70000, SELL 0
        whenever(orderRequestRepo.aggregateFilledByStrategyAndSide("user-A", "005930", TradingMode.MOCK))
            .thenReturn(listOf(ledgerRow(8L, OrderSide.BUY, "3", "210000")))

        adapter.reconcileStrategyPositionDrift("user-A", "005930", "MOCK")

        val saved = port.upserts.single()
        assertThat(saved.strategyId).isEqualTo(8L)
        assertThat(saved.quantity).isEqualTo(3)
        // 평단 = 210000 notional / 3 qty = 70000 (reset 0 → ledger BUY 가중평단 복원).
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal("70000"))
        // realized_pnl 보존(0).
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal.ZERO)
        // 감사 이벤트 — DRIFT_CORRECTED 1건.
        assertThat(emittedEvents.map { it.action }).contains("STRATEGY_POSITION_DRIFT_CORRECTED")
    }

    @Test
    fun `모호 귀속 — ledger net2 인데 KIS 3주(외부 1주 혼재) → 보정 안 함 WARN-only`() {
        port.legs = mutableListOf(strategyPosition(strategyId = 8L, quantity = 0, avgPrice = "0"))
        whenever(positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK"))
            .thenReturn(positionEntity(quantity = 3))
        whenever(orderRequestRepo.aggregateFilledByStrategyAndSide("user-A", "005930", TradingMode.MOCK))
            .thenReturn(listOf(ledgerRow(8L, OrderSide.BUY, "2", "140000")))

        adapter.reconcileStrategyPositionDrift("user-A", "005930", "MOCK")

        // 보정 upsert 없음.
        assertThat(port.upserts).isEmpty()
        // WARN 이벤트만.
        assertThat(emittedEvents.map { it.action }).contains("STRATEGY_POSITION_DRIFT")
        assertThat(emittedEvents.map { it.action }).doesNotContain("STRATEGY_POSITION_DRIFT_CORRECTED")
    }

    @Test
    fun `over-count 0 — 정합 상태 재실행 시 추가 upsert 없음(멱등)`() {
        // 이미 정합 상태: leg qty=3, KIS=3, ledger net=3.
        port.legs = mutableListOf(strategyPosition(strategyId = 8L, quantity = 3, avgPrice = "70000"))
        whenever(positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK"))
            .thenReturn(positionEntity(quantity = 3))
        whenever(orderRequestRepo.aggregateFilledByStrategyAndSide("user-A", "005930", TradingMode.MOCK))
            .thenReturn(listOf(ledgerRow(8L, OrderSide.BUY, "3", "210000")))

        adapter.reconcileStrategyPositionDrift("user-A", "005930", "MOCK")

        // 정합(strategySum==authority) → PositionDriftCorrection NoChange → upsert 0.
        assertThat(port.upserts).isEmpty()
    }

    @Test
    fun `SELL 차감 후 부족 — BUY5 SELL2 net3 = KIS3 → leg0 을 3 으로 정정`() {
        port.legs = mutableListOf(strategyPosition(strategyId = 8L, quantity = 0, avgPrice = "0"))
        whenever(positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK"))
            .thenReturn(positionEntity(quantity = 3))
        whenever(orderRequestRepo.aggregateFilledByStrategyAndSide("user-A", "005930", TradingMode.MOCK))
            .thenReturn(
                listOf(
                    ledgerRow(8L, OrderSide.BUY, "5", "350000"),
                    ledgerRow(8L, OrderSide.SELL, "2", "150000"),
                ),
            )

        adapter.reconcileStrategyPositionDrift("user-A", "005930", "MOCK")

        val saved = port.upserts.single()
        assertThat(saved.quantity).isEqualTo(3)
        // 평단 = BUY notional 350000 / BUY qty 5 = 70000 (SELL 은 평단 산정 제외).
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal("70000"))
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun strategyPosition(
        strategyId: Long,
        quantity: Int,
        avgPrice: String,
        realizedPnl: String = "0",
    ): StrategyPosition =
        StrategyPosition(
            id = strategyId,
            userId = "user-A",
            strategyId = strategyId,
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal(avgPrice),
            filledLots = if (quantity > 0) 1 else 0,
            realizedPnl = BigDecimal(realizedPnl),
            executedExitStages = emptySet(),
            openedAt = NOW,
            updatedAt = NOW,
        )

    private fun positionEntity(quantity: Int): PositionJpaEntity =
        PositionJpaEntity(
            userId = "user-A",
            code = "005930",
            quantity = quantity,
        )

    private fun ledgerRow(
        strategyId: Long,
        side: OrderSide,
        qty: String,
        notional: String,
    ): StrategyLedgerRow =
        object : StrategyLedgerRow {
            override val strategyId: Long = strategyId
            override val side: OrderSide = side
            override val qtySum: BigDecimal = BigDecimal(qty)
            override val notionalSum: BigDecimal = BigDecimal(notional)
        }

    private companion object {
        val NOW: kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochSeconds(1_736_640_000L)
    }

    /** 다전략 leg 를 in-memory 로 보유·갱신하는 port (부족 보정 시나리오 검증). */
    private class MultiLegCapturingPort : StrategyPositionPort {
        var legs: MutableList<StrategyPosition> = mutableListOf()
        val upserts: MutableList<StrategyPosition> = mutableListOf()

        override fun findByUserStrategyCode(
            userId: String,
            strategyId: Long,
            code: String,
        ): StrategyPosition? = legs.find { it.strategyId == strategyId }

        override fun findByUserCode(
            userId: String,
            code: String,
        ): List<StrategyPosition> = legs.toList()

        override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
            upserts += strategyPosition
            legs.removeAll { it.strategyId == strategyPosition.strategyId }
            legs += strategyPosition
            return strategyPosition
        }
    }
}
