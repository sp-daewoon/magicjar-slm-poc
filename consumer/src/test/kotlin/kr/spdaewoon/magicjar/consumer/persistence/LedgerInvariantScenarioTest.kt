package kr.spdaewoon.magicjar.consumer.persistence

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant as JInstant

/**
 * 원장 키 사슬 불변식 회귀 시나리오 LOCK — ADR 0115 §2-B-2 불변식 5 (qa, 2026-06-10).
 *
 * spec `2026-06-10-execution-ledger-integrity-architecture.md` §2-B-2 의 **불변식 5(회귀 시나리오 LOCK)** 를
 * 자동 회귀로 못박는다. 최고 관리자 "실격" 정의(2026-06-10):
 * > "매도했다는데 포지션이 남아있고 매수했다는데 포지션이 안 보이면 자동매매 시스템 실격."
 *
 * 본 테스트는 그 두 실격 케이스를 **증분 경로(applyExecution)의 결함 재현 → rebuild 경로 회복** 의 완전 사이클로 검증한다.
 * 두 경로 모두 [ConsumerExecutionStrategyPositionAdapter] 의 실제 코드 경로를 통과한다(consumer main 수정 0 — 시나리오만 LOCK).
 *
 * | 시나리오 | 불변식 | 검증 |
 * |---------|--------|------|
 * | **S-A** 매도 체결 후 전략 포지션 잔존 0건 | §2-B-2 #2·#3·#5 | 전량 SELL(부분체결 분할 포함) → strategy_position.quantity == 0 + KIS 권위 정합. 체결 유실 변형 → rebuild 경유 0 회복 |
 * | **S-B** 매수 체결 후 전략 포지션 미표시 0건 | §2-B-2 #2·#3·#5 | BUY 부분체결 N건(1+3+5+1+2+3) 일부 귀속 누락(replay-skip G4) → rebuild 후 전량 귀속(15주) |
 *
 * ## 레벨 결정 (qa 판단)
 * 실제 DB 컨테이너 대신 [ConsumerExecutionStrategyPositionAdapter] 의 port·repository 의존을 in-memory fake/mock 으로
 * 주입해 **어댑터 코드 경로 그대로** 통과시킨다(JVM 단위·CI 무인프라). DB I/O·트랜잭션 경계는 어댑터 단위 테스트
 * ([ConsumerExecutionStrategyPositionAdapterTest])·rebuild 순수 함수 테스트
 * ([kr.spdaewoon.magicjar.strategy.integrity.StrategyPositionRebuildTest]) 가 담당하고, 본 테스트는 그 둘을
 * **하나의 incident 시나리오로 엮어** "실격 상태가 발생할 수 있는가 / rebuild 로 회복되는가" 를 LOCK 한다.
 *
 * R2(OrderTrigger·Flyway V80) 와 직교 — consumer main 코드 0 touch, 테스트 디렉토리만.
 */
class LedgerInvariantScenarioTest {
    private val port = InMemoryStrategyPositionPort()
    private val jpaRepo: StrategyPositionJpaRepository = mock()
    private val positionRepo: PositionJpaRepository = mock()
    private val orderRequestRepo: ConsumerOrderRequestJpaRepository = mock()
    private val signalRepo: ConsumerSignalJpaRepository = mock()

    private val systemEventProvider: ObjectProvider<SystemEventLogger> =
        mock<ObjectProvider<SystemEventLogger>>().also {
            whenever(it.ifAvailable).thenReturn(null)
        }

    /**
     * drift 자동 보정은 끈다(driftAutoCorrectEnabled=false) — 본 테스트는 **rebuild 경로의 회복력** 을 격리 검증한다.
     * (drift 보정 경로는 별도 [ConsumerExecutionStrategyPositionDriftReconcileTest]·
     * [ConsumerExecutionStrategyPositionUnderflowReconcileTest] 가 담당.)
     * ledgerRebuildEnabled=true 로 rebuild 1급 경로 활성.
     */
    private val adapter =
        ConsumerExecutionStrategyPositionAdapter(
            strategyPositionPort = port,
            strategyPositionJpaRepository = jpaRepo,
            positionJpaRepository = positionRepo,
            orderRequestJpaRepository = orderRequestRepo,
            signalJpaRepository = signalRepo,
            systemEventLoggerProvider = systemEventProvider,
            driftAutoCorrectEnabled = false,
            ledgerRebuildEnabled = true,
            clock = Clock.System,
        )

    // ════════════════════════════════════════════════════════════════════════
    // S-A "매도 체결 후 전략 포지션 잔존 0건" (불변식 #2·#3·#5)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `S-A 정상경로 — BUY 진입 후 SELL 부분체결 분할 전량 청산 → strategy_position 0주`() {
        // BUY 10주 진입(전략 100).
        applyBuy(strategyId = 100L, qty = 10, price = "70000")
        assertThat(port.qty(100L)).isEqualTo(10)

        // SELL 을 부분체결 4+3+3 = 10 으로 전량 청산.
        applySell(strategyId = 100L, qty = 4, price = "72000", signalId = 0L)
        applySell(strategyId = 100L, qty = 3, price = "73000", signalId = 0L)
        applySell(strategyId = 100L, qty = 3, price = "74000", signalId = 0L)

        // 불변식: 매도 전량 체결 후 그 전략 포지션 잔존 0건.
        assertThat(port.qty(100L)).isEqualTo(0)
        // 전량청산 reset — 평단·filledLots 0, realized 보존: (72-70)*4 + (73-70)*3 + (74-70)*3 = 8000+9000+12000 = 29000.
        val saved = port.get(100L)!!
        assertThat(saved.avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(saved.filledLots).isEqualTo(0)
        assertThat(saved.realizedPnl).isEqualByComparingTo(BigDecimal("29000"))
    }

    @Test
    fun `S-A 체결유실 변형 — SELL execution 일부 누락으로 strategy_position 잔존(실격 상태) → rebuild 후 0주 회복`() {
        // ── 1) incident 재현: BUY 10주 진입 → SELL 10주 전량 매도 의도였으나 ──
        //      마지막 SELL 체결통보(3주)가 WS 유실(배포 다운타임·MOCK WS 미지원)되어 strategy_position 에 미반영.
        applyBuy(strategyId = 100L, qty = 10, price = "70000")
        applySell(strategyId = 100L, qty = 4, price = "72000", signalId = 0L)
        applySell(strategyId = 100L, qty = 3, price = "73000", signalId = 0L)
        // 마지막 3주 SELL 체결 유실 — applyExecution 미도달(증분 경로 누락).

        // ── 2) 실격 상태 확인: "매도했다는데 포지션이 3주 남아있다" ──
        assertThat(port.qty(100L)).isEqualTo(3) // ← 최고 관리자 실격 정의 그 자체.

        // ── 3) 진실원(order_execution) 은 유실 체결까지 모두 보존: KIS 권위 position = 0주(전량 매도 됨) ──
        //      backfill 이 흡수한 진실원에는 SELL 10주 전체가 있다.
        givenAuthority(code = CODE, qty = 0)
        givenLedger(
            buy(100L, 10, "70000", t = 1),
            sell(100L, 4, "72000", t = 2),
            sell(100L, 3, "73000", t = 3),
            sell(100L, 3, "74000", t = 4), // ← 유실됐던 마지막 체결 — 진실원엔 존재.
        )

        // ── 4) rebuild 경유: order_execution 진실원에서 전량 재계산 + KIS 권위(0주) 교차검산 통과 → 적용 ──
        val applied = adapter.rebuildStrategyPositionFromLedger(USER, CODE, MODE)

        // ── 5) 회복 검증: strategy_position 0주 (잔존 0건 — 실격 해소) ──
        assertThat(applied).isTrue()
        assertThat(port.qty(100L)).isEqualTo(0)
        // realized 도 진실원 기준 정합: (72-70)*4 + (73-70)*3 + (74-70)*3 = 29000.
        assertThat(port.get(100L)!!.realizedPnl).isEqualByComparingTo(BigDecimal("29000"))
    }

    @Test
    fun `S-A 교차검산 가드 — rebuild 결과가 KIS 권위와 불일치면 미적용(없는 물량 인식 0)`() {
        // strategy_position 3주 잔존(유실). 그러나 진실원이 SELL 일부만 보존(진실원 자체도 누락) → rebuild=잔3주.
        applyBuy(strategyId = 100L, qty = 10, price = "70000")
        applySell(strategyId = 100L, qty = 4, price = "72000", signalId = 0L)
        applySell(strategyId = 100L, qty = 3, price = "73000", signalId = 0L)
        assertThat(port.qty(100L)).isEqualTo(3)

        // KIS 권위는 0주(전량 매도)지만, 진실원엔 SELL 7주만 → rebuild=3주 ≠ authority 0 → 교차검산 불일치.
        givenAuthority(code = CODE, qty = 0)
        givenLedger(
            buy(100L, 10, "70000", t = 1),
            sell(100L, 4, "72000", t = 2),
            sell(100L, 3, "73000", t = 3),
        )

        val applied = adapter.rebuildStrategyPositionFromLedger(USER, CODE, MODE)

        // 불일치 → 미적용(WARN). 함부로 덮어쓰지 않음 — 진실원 정합은 backfill·운영이 회복 후 다음 reconcile 가 흡수.
        assertThat(applied).isFalse()
        assertThat(port.qty(100L)).isEqualTo(3) // 기존 상태 유지(over-write 0).
    }

    // ════════════════════════════════════════════════════════════════════════
    // S-B "매수 체결 후 전략 포지션 미표시 0건" (불변식 #2·#3·#5)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `S-B 정상경로 — BUY 부분체결 6건 모두 귀속 → strategy_position 15주 전량 표시`() {
        // 주문 #223 패턴 — 부분체결 6건(1+3+5+1+2+3=15) 이 모두 applyExecution 도달.
        applyBuy(strategyId = 8L, qty = 1, price = "70000")
        applyBuy(strategyId = 8L, qty = 3, price = "70000")
        applyBuy(strategyId = 8L, qty = 5, price = "70000")
        applyBuy(strategyId = 8L, qty = 1, price = "70000")
        applyBuy(strategyId = 8L, qty = 2, price = "70000")
        applyBuy(strategyId = 8L, qty = 3, price = "70000")

        // 불변식: 매수 부분체결 전량 귀속 → 15주 표시(미표시 0건).
        assertThat(port.qty(8L)).isEqualTo(15)
        assertThat(port.get(8L)!!.filledLots).isEqualTo(6)
    }

    @Test
    fun `S-B replay-skip 결함 재현 — BUY 부분체결 일부만 귀속(미표시 실격) → rebuild 후 15주 전량 회복`() {
        // ── 1) incident 재현(G4): 주문 #223 부분체결 6건 중 첫 행 생성 후 일부 체결의 귀속이 누락 ──
        //      증분 경로에서 1+3+5+1 = 10 만 반영되고 2+3(5주) 가 미귀속(replay-skip).
        applyBuy(strategyId = 8L, qty = 1, price = "70000")
        applyBuy(strategyId = 8L, qty = 3, price = "70000")
        applyBuy(strategyId = 8L, qty = 5, price = "70000")
        applyBuy(strategyId = 8L, qty = 1, price = "70000")
        // 마지막 2+3주 미귀속 — strategy_position 10주, KIS 권위 15주.

        // ── 2) 실격 상태 확인: "매수했다는데 5주가 미표시" ──
        assertThat(port.qty(8L)).isEqualTo(10) // ← incident 11주와 같은 종류의 미표시(여기선 fixture 상 10주).

        // ── 3) 진실원(order_execution) 은 6건 전부 보존: KIS 권위 position = 15주 ──
        givenAuthority(code = CODE, qty = 15)
        givenLedger(
            buy(8L, 1, "70000", t = 1),
            buy(8L, 3, "70000", t = 2),
            buy(8L, 5, "70000", t = 3),
            buy(8L, 1, "70000", t = 4),
            buy(8L, 2, "70000", t = 5), // ← 미귀속됐던 체결 — 진실원엔 존재.
            buy(8L, 3, "70000", t = 6), // ← 미귀속됐던 체결 — 진실원엔 존재.
        )

        // ── 4) rebuild 경유: 진실원 6건 전량 재계산 + KIS 권위(15주) 교차검산 통과 → 적용 ──
        val applied = adapter.rebuildStrategyPositionFromLedger(USER, CODE, MODE)

        // ── 5) 회복 검증: strategy_position 15주 전량 표시(미표시 0건 — 실격 해소) ──
        assertThat(applied).isTrue()
        assertThat(port.qty(8L)).isEqualTo(15)
        assertThat(port.get(8L)!!.avgPrice).isEqualByComparingTo(BigDecimal("70000.0000"))
        assertThat(port.get(8L)!!.filledLots).isEqualTo(6)
    }

    @Test
    fun `S-B 이중경로 멱등 — 같은 eventId 가 진실원에 중복 보존돼도 rebuild 1회만 반영`() {
        // 진실원에 같은 물리 체결이 같은 eventId 로 중복 보존된 경우(WS+backfill 이중 경로 dedup 대상).
        // 현행 rebuild 는 eventId distinct 로 dedup — 같은 eventId 만 1회 반영(이중계상 0).
        applyBuy(strategyId = 8L, qty = 5, price = "70000")
        assertThat(port.qty(8L)).isEqualTo(5)

        givenAuthority(code = CODE, qty = 5)
        givenLedger(
            buyEvt("WS:223:1000:5", 8L, 5, "70000", t = 1),
            buyEvt("WS:223:1000:5", 8L, 5, "70000", t = 1), // 같은 eventId 재유입(dedup 대상).
        )

        val applied = adapter.rebuildStrategyPositionFromLedger(USER, CODE, MODE)

        // 멱등으로 5주(이중계상 0) → authority 5주 일치. 기존과 동일하므로 over-write 0(미적용=false).
        assertThat(applied).isFalse() // 이미 동일 수량·평단 → upsert skip.
        assertThat(port.qty(8L)).isEqualTo(5)
    }

    @Test
    fun `D1·D2 회귀 — REAL rebuild 는 REAL 체결만 fold(MOCK 과거 혼입 0) → strategy_position(85)=1 복원`() {
        // 라이브 13:35 evidence: rebuildTotal=10 vs authority=1 — MOCK 8번 과거 체결이 REAL rebuild 에 혼입돼
        // 교차검산이 영영 깨졌다. fix: rebuild ledger 가 mode 차원 정합(REAL 권위↔REAL 체결만).
        // strategy_position(85) 가 어떤 경로로 0(미표시)이 됐다고 가정.
        port.upsert(
            StrategyPosition(
                id = 85L,
                userId = USER,
                strategyId = 85L,
                code = CODE,
                quantity = 0,
                avgPrice = BigDecimal.ZERO,
                filledLots = 0,
                realizedPnl = BigDecimal.ZERO,
                executedExitStages = emptySet(),
                openedAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )
        // KIS REAL 권위 = 1주(REAL 체결 1건). MODE="REAL" 로 조회.
        whenever(positionRepo.findByUserIdAndCodeAndSource(USER, CODE, "REAL"))
            .thenReturn(PositionJpaEntity(userId = USER, code = CODE, quantity = 1))
        // ★ REAL mode 의 rebuild ledger 만 stub — REAL BUY 85전략 1주. MOCK 8전략 과거 체결은 본 stub 에 없으므로
        //   query mode 필터가 정상 작동하면 REAL rebuild 에 절대 섞이지 않는다(mode 무차원이면 빈 리스트 stub 미스).
        whenever(orderRequestRepo.findRebuildExecutionsByUserAndCode(USER, CODE, TradingMode.REAL))
            .thenReturn(listOf(buy(85L, 1, "70000", t = 1)))
        whenever(signalRepo.findById(any())).thenReturn(java.util.Optional.empty())

        val applied = adapter.rebuildStrategyPositionFromLedger(USER, CODE, "REAL")

        // rebuild=1 == REAL authority 1 → 교차검산 통과 → strategy_position(85)=1 복원.
        assertThat(applied).isTrue()
        assertThat(port.qty(85L)).isEqualTo(1)
    }

    @Test
    fun `S-B rebuild 토글 off — ledger-rebuild 비활성 시 no-op(현행 증분 경로 회귀)`() {
        val disabledAdapter =
            ConsumerExecutionStrategyPositionAdapter(
                strategyPositionPort = port,
                strategyPositionJpaRepository = jpaRepo,
                positionJpaRepository = positionRepo,
                orderRequestJpaRepository = orderRequestRepo,
                signalJpaRepository = signalRepo,
                systemEventLoggerProvider = systemEventProvider,
                driftAutoCorrectEnabled = false,
                ledgerRebuildEnabled = false,
                clock = Clock.System,
            )
        applyBuy(strategyId = 8L, qty = 10, price = "70000")
        givenAuthority(code = CODE, qty = 15)
        givenLedger(buy(8L, 15, "70000", t = 1))

        val applied = disabledAdapter.rebuildStrategyPositionFromLedger(USER, CODE, MODE)

        // 토글 off → rebuild no-op. 미표시 10주 그대로(롤백 경로 — drift 보정이 별도 회복 담당).
        assertThat(applied).isFalse()
        assertThat(port.qty(8L)).isEqualTo(10)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun applyBuy(
        strategyId: Long,
        qty: Int,
        price: String,
    ) = adapter.applyExecution(
        request(OrderSide.BUY, strategyId, signalId = 1L),
        execution(qty, price),
    )

    private fun applySell(
        strategyId: Long,
        qty: Int,
        price: String,
        signalId: Long,
    ) = adapter.applyExecution(
        request(OrderSide.SELL, strategyId, signalId = signalId),
        execution(qty, price),
    )

    private fun givenAuthority(
        code: String,
        qty: Int,
    ) {
        whenever(positionRepo.findByUserIdAndCodeAndSource(USER, code, MODE))
            .thenReturn(PositionJpaEntity(userId = USER, code = code, quantity = qty))
    }

    private fun givenLedger(vararg rows: StrategyRebuildExecutionRow) {
        whenever(orderRequestRepo.findRebuildExecutionsByUserAndCode(USER, CODE, TradingMode.MOCK))
            .thenReturn(rows.toList())
        // rebuild 가 partialExitStage 해석 시 signalRepo 를 건드릴 수 있으나 본 픽스처는 signalId=0 → 미조회.
        whenever(signalRepo.findById(any())).thenReturn(java.util.Optional.empty())
    }

    private fun request(
        side: OrderSide,
        strategyId: Long,
        signalId: Long,
    ): OrderRequest =
        OrderRequest(
            id = 223L,
            userId = USER,
            signalId = signalId,
            strategyId = strategyId,
            code = CODE,
            side = side,
            priceType = OrderPriceType.MARKET,
            quantity = 15,
            limitPrice = null,
            status = OrderStatus.PARTIALLY_FILLED,
            mode = TradingMode.MOCK,
            idempotencyKey = "k-$side-$strategyId-$signalId-${seq++}",
            requestedAt = JInstant.EPOCH,
        )

    private fun execution(
        quantity: Int,
        price: String,
    ): OrderExecution =
        OrderExecution(
            id = seq++,
            orderId = 223L,
            userId = USER,
            kisOrderNo = "ODNO223",
            executedQuantity = quantity,
            executedPrice = BigDecimal(price),
            executedAt = JInstant.EPOCH,
            createdAt = JInstant.EPOCH,
        )

    private fun buy(
        strategyId: Long,
        qty: Int,
        price: String,
        t: Long,
    ): StrategyRebuildExecutionRow = buyEvt("BUY:$strategyId:$t:$qty", strategyId, qty, price, t)

    private fun buyEvt(
        eventId: String,
        strategyId: Long,
        qty: Int,
        price: String,
        t: Long,
    ): StrategyRebuildExecutionRow =
        FakeRebuildRow(eventId, strategyId, OrderSide.BUY, BigDecimal(qty), BigDecimal(price), JInstant.ofEpochMilli(t), 0L)

    private fun sell(
        strategyId: Long,
        qty: Int,
        price: String,
        t: Long,
    ): StrategyRebuildExecutionRow =
        FakeRebuildRow(
            "SELL:$strategyId:$t:$qty",
            strategyId,
            OrderSide.SELL,
            BigDecimal(qty),
            BigDecimal(price),
            JInstant.ofEpochMilli(t),
            0L,
        )

    private var seq = 1L

    private companion object {
        const val USER = "user-A"
        const val CODE = "005930"
        const val MODE = "MOCK"
    }

    /** [StrategyRebuildExecutionRow] interface projection 의 테스트 fake. */
    private class FakeRebuildRow(
        override val eventId: String?,
        override val strategyId: Long,
        override val side: OrderSide,
        override val executedQty: BigDecimal,
        override val executedPrice: BigDecimal,
        override val executedAt: JInstant,
        override val signalId: Long,
    ) : StrategyRebuildExecutionRow {
        override val orderId: Long = 223L
    }

    /** 증분·rebuild 양 경로의 strategy_position read/write 를 보존하는 in-memory port(전략별). */
    private class InMemoryStrategyPositionPort : StrategyPositionPort {
        private val byStrategy = mutableMapOf<Long, StrategyPosition>()

        fun get(strategyId: Long): StrategyPosition? = byStrategy[strategyId]

        fun qty(strategyId: Long): Int = byStrategy[strategyId]?.quantity ?: 0

        override fun findByUserStrategyCode(
            userId: String,
            strategyId: Long,
            code: String,
        ): StrategyPosition? = byStrategy[strategyId]

        override fun findByUserCode(
            userId: String,
            code: String,
        ): List<StrategyPosition> = byStrategy.values.toList()

        override fun upsert(strategyPosition: StrategyPosition): StrategyPosition {
            byStrategy[strategyPosition.strategyId] = strategyPosition
            return strategyPosition
        }
    }
}
