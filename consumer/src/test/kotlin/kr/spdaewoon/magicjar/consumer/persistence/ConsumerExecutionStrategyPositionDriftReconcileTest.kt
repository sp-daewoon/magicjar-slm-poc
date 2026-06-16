package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant as JInstant

/**
 * ADR 0115 §P1-1 R2 배선 테스트 — reconcileDrift 가 R1 [PositionDriftCorrection] 결과를 strategy_position DB 에 반영.
 *
 * incident(7 vs 5) 회귀 가드: 초과 drift → 권위(position) 정렬 UPDATE, realized_pnl 불변. 부족 → 보정 안 함(WARN 유지).
 * SELL 체결 후 호출되는 reconcileDrift 의 최종 upsert(보정)를 capture 해 단언한다.
 */
class ConsumerExecutionStrategyPositionDriftReconcileTest {
    private val port = ReconcilingStrategyPositionPort()
    private val jpaRepo: StrategyPositionJpaRepository = mock()
    private val positionRepo: PositionJpaRepository = mock()
    private val orderRequestRepo: ConsumerOrderRequestJpaRepository = mock()
    private val signalRepo: ConsumerSignalJpaRepository = mock()

    private val systemEventProvider: ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger> =
        mock<ObjectProvider<kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger>>().also {
            whenever(it.ifAvailable).thenReturn(null)
        }

    private fun adapter(enabled: Boolean) =
        ConsumerExecutionStrategyPositionAdapter(
            strategyPositionPort = port,
            strategyPositionJpaRepository = jpaRepo,
            positionJpaRepository = positionRepo,
            orderRequestJpaRepository = orderRequestRepo,
            signalJpaRepository = signalRepo,
            systemEventLoggerProvider = systemEventProvider,
            driftAutoCorrectEnabled = enabled,
            clock = Clock.System,
        )

    @Test
    fun `초과 drift 7 vs position 5 — strategy_position 5로 보정 UPDATE + realized_pnl 불변`() {
        // strategy_position 7주(체결 직후 leg 합) vs KIS position 5주 권위 → 2주 초과 내림.
        port.legs =
            listOf(
                leg(strategyId = 100L, quantity = 7, realizedPnl = "30000", avgPrice = "10000"),
            )
        whenever(positionRepo.findByUserIdAndCodeAndSource(eq("user-A"), eq("005930"), any()))
            .thenReturn(positionEntity(quantity = 5))

        // SELL 체결(1주) — applySell 이 1차 upsert, 그 후 reconcileDrift 가 보정 2차 upsert.
        port.preset = port.legs.first()
        adapter(enabled = true).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        val corrected = port.lastUpsert!!
        assertThat(corrected.quantity).isEqualTo(5)
        // realized_pnl 은 보정으로 건드리지 않는다(보정 직전 applySell 이 산정한 값 그대로 — 수량만 정렬).
        assertThat(corrected.realizedPnl).isEqualByComparingTo(BigDecimal("30000"))
        assertThat(corrected.avgPrice).isEqualByComparingTo(BigDecimal("10000"))
    }

    @Test
    fun `초과분 전량 보정으로 0 도달 — avgPrice·filledLots·executedExitStages reset`() {
        // 2026-06-15 fix: "권위 0 + leg 양수 → 0 reset" 보정은 **주기 reconcile**(원장이 BalanceReconcile 로
        // 채워진 뒤 = 진짜 미보유) 경로에서만 일어난다. 체결 직후(applyExecution, postExecution=true) 경로는
        // 원장 0 이 "미반영 빈틈" 일 수 있어 깎지 않는다(아래 별도 테스트). 따라서 reset 검증은 주기 reconcile 로 한다.
        port.legs =
            listOf(leg(strategyId = 100L, quantity = 3, realizedPnl = "5000", avgPrice = "10000", filledLots = 2, stages = setOf(0)))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 0))

        port.preset = port.legs.first()
        adapter(enabled = true).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")

        val corrected = port.lastUpsert!!
        assertThat(corrected.quantity).isEqualTo(0)
        assertThat(corrected.avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(corrected.filledLots).isEqualTo(0)
        assertThat(corrected.executedExitStages).isEmpty()
        // realized_pnl 은 reset 대상 아님(보정은 수량 정렬).
        assertThat(corrected.realizedPnl).isEqualByComparingTo(BigDecimal("5000"))
    }

    @Test
    fun `체결 직후 권위 0(행 존재) + leg 양수 — 초과 삭제 보류(2026-06-15 drift 근본 fix)`() {
        // 005930·전략 88 실측: 15:10 매수 체결 직후 reconcileDrift 시점에 원장 행은 존재하나 quantity=0
        // (원장 반영은 15:51) → 기존엔 legs > authority(0) 초과로 방금 귀속한 물량을 즉시 삭제했다.
        // fix: postExecution=true + 권위 0(행 존재) + leg 양수면 깎지 않고 보존(다음 주기 회복).
        port.legs = listOf(leg(strategyId = 88L, quantity = 1, realizedPnl = "0", avgPrice = "75000"))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 0))

        // 첫 진입 — applyBuy(+1) 후 같은 transaction 에서 reconcileDrift(postExecution=true).
        port.preset = null
        adapter(enabled = true).applyExecution(
            request(side = OrderSide.BUY, strategyId = 88L),
            execution(quantity = 1, price = "75000"),
        )

        // 방금 귀속한 1주가 보존(drift 가 0 으로 깎지 않음).
        val saved = port.lastUpsert!!
        assertThat(saved.strategyId).isEqualTo(88L)
        assertThat(saved.quantity).isEqualTo(1)
    }

    @Test
    fun `부족 drift — 보정하지 않음(WarnUnderflow 경로, 마지막 upsert가 보정 아님)`() {
        // strategy_position 2주 < position 5주 → 외부 매수 등 — 보정 안 함(없는 물량 인식 회피).
        port.legs = listOf(leg(strategyId = 100L, quantity = 2, realizedPnl = "0", avgPrice = "10000"))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 5))

        port.preset = port.legs.first()
        port.upsertCount = 0
        adapter(enabled = true).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        // applySell upsert 1회만 — 부족 방향은 reconcile 보정 upsert 0(WARN만).
        assertThat(port.upsertCount).isEqualTo(1)
    }

    @Test
    fun `D1 회귀 — 권위 position 행 부재(null)면 leg 양수여도 내림 보정 0 (정상 귀속 파괴 폐지)`() {
        // 라이브 13:31:01.963 evidence: REAL 체결로 strategy_position(85)=1 정상 귀속 직후, 잘못된 source(또는
        // 아직 안 쓰인 권위 행) 조회로 authority=0 판정 → strategySum=1->0 으로 정상 귀속을 즉시 삭제했다.
        // fix: 그 source 의 position 행이 **부재(null)** 면 "확정 0" 이 아니므로 leg 양수면 보정하지 않고 skip.
        port.legs = listOf(leg(strategyId = 85L, quantity = 1, realizedPnl = "0", avgPrice = "70000"))
        // 권위 행 자체 부재 — null 반환(첫 REAL 체결 직후·source 미스 시뮬).
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any())).thenReturn(null)

        port.upsertCount = 0
        adapter(enabled = true).reconcileStrategyPositionDrift("user-A", "005930", "REAL")

        // 행 부재 + leg 양수 → 보정 upsert 0(정상 귀속 보존). 다음 주기에 권위 행 생기면 정합.
        assertThat(port.upsertCount).isEqualTo(0)
    }

    @Test
    fun `유령 over-count — 체결 없이 reconcileStrategyPositionDrift 직접 호출, position 0주에 3주 leg 내림 reset`() {
        // 2026-06-10 오후 incident 재현: AUTO SELL 체결이 strategy_position 에 미반영(차감 누락)으로
        // KIS(position) 0주인데 strategy_position 3주 잔존. worker 가 체결 없이 reconcileStrategyPositionDrift 를
        // 직접 호출 → Adjust(초과 전량 내림) 경로가 positionQty=0 에서도 정상 작동해야 한다.
        port.legs =
            listOf(leg(strategyId = 8L, quantity = 3, realizedPnl = "0", avgPrice = "55000", filledLots = 1, stages = setOf(0)))
        whenever(positionRepo.findByUserIdAndCodeAndSource(eq("user-A"), eq("005930"), eq("MOCK")))
            .thenReturn(positionEntity(quantity = 0))

        port.upsertCount = 0
        adapter(enabled = true).reconcileStrategyPositionDrift("user-A", "005930", "MOCK")

        // 체결 경로(applySell) 없이 보정 upsert 1회만 — leg 3→0 reset.
        assertThat(port.upsertCount).isEqualTo(1)
        val corrected = port.lastUpsert!!
        assertThat(corrected.quantity).isEqualTo(0)
        assertThat(corrected.avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(corrected.filledLots).isEqualTo(0)
        assertThat(corrected.executedExitStages).isEmpty()
    }

    @Test
    fun `보정 비활성(ENV off) — 초과여도 보정 upsert 0(현행 WARN-only fallback)`() {
        port.legs = listOf(leg(strategyId = 100L, quantity = 7, realizedPnl = "0", avgPrice = "10000"))
        whenever(positionRepo.findByUserIdAndCodeAndSource(any(), any(), any()))
            .thenReturn(positionEntity(quantity = 5))
        whenever(jpaRepo.sumQuantityByUserAndCode(any(), any())).thenReturn(7)

        port.preset = port.legs.first()
        port.upsertCount = 0
        adapter(enabled = false).applyExecution(
            request(side = OrderSide.SELL, strategyId = 100L),
            execution(quantity = 1, price = "10000"),
        )

        // applySell upsert 1회만 — Disabled 분기는 보정 upsert 0.
        assertThat(port.upsertCount).isEqualTo(1)
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun leg(
        strategyId: Long,
        quantity: Int,
        realizedPnl: String,
        avgPrice: String,
        filledLots: Int = 1,
        stages: Set<Int> = emptySet(),
    ): StrategyPosition =
        StrategyPosition(
            id = strategyId,
            userId = "user-A",
            strategyId = strategyId,
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal(avgPrice),
            filledLots = filledLots,
            realizedPnl = BigDecimal(realizedPnl),
            executedExitStages = stages,
            openedAt = NOW,
            updatedAt = NOW,
        )

    private fun positionEntity(quantity: Int): PositionJpaEntity =
        PositionJpaEntity(
            userId = "user-A",
            code = "005930",
            source = "MOCK",
            quantity = quantity,
            avgPrice = BigDecimal("10000"),
            updatedAt = JInstant.EPOCH,
        )

    private fun request(
        side: OrderSide,
        strategyId: Long,
    ): OrderRequest =
        OrderRequest(
            id = 7L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = 1,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            idempotencyKey = "k-$side-$strategyId",
            requestedAt = JInstant.EPOCH,
        )

    private fun execution(
        quantity: Int,
        price: String,
    ): OrderExecution =
        OrderExecution(
            id = 1L,
            orderId = 7L,
            userId = "user-A",
            kisOrderNo = "ODNO1",
            executedQuantity = quantity,
            executedPrice = BigDecimal(price),
            executedAt = JInstant.EPOCH,
            createdAt = JInstant.EPOCH,
        )

    private companion object {
        val NOW: kotlinx.datetime.Instant = kotlinx.datetime.Instant.fromEpochSeconds(1_736_640_000L)
    }

    /** applySell 1차 + reconcileDrift 보정 2차 upsert 를 모두 capture. findByUserCode 는 leg 목록 반환. */
    private class ReconcilingStrategyPositionPort : StrategyPositionPort {
        var preset: StrategyPosition? = null
        var legs: List<StrategyPosition> = emptyList()
        var lastUpsert: StrategyPosition? = null
        var upsertCount: Int = 0

        override fun findByUserStrategyCode(
            userId: String,
            strategyId: Long,
            code: String,
        ): StrategyPosition? = preset

        override fun findByUserCode(
            userId: String,
            code: String,
        ): List<StrategyPosition> = legs

        override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
            lastUpsert = strategyPosition
            upsertCount++
            return strategyPosition
        }
    }
}
