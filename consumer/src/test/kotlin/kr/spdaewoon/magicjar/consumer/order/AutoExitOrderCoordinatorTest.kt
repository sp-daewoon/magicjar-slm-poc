package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.ingestion.HoldingDemandRegistrar
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * [AutoExitOrderCoordinator] 단위 테스트 — ADR 0114 R2-C2b ② 체결 즉시 TP 선주문 + scale-in 재발행.
 *
 * 커버: BUY 체결 TP 선주문 · scale-in 평단 변동 재발행 · 정합 시 재발행 0(중복 방지) · HOLDING 등록 · SELL 전량 청산 release.
 */
class AutoExitOrderCoordinatorTest {
    private val objectMapper = ObjectMapper()
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>()
    private val positionRepo = mockk<StrategyPositionJpaRepository>()
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val index = mockk<HoldingPositionIndex>(relaxed = true)
    private val registrar = mockk<HoldingDemandRegistrar>(relaxed = true)

    private fun coordinator(tpEnabled: Boolean = true) =
        AutoExitOrderCoordinator(
            objectMapper = objectMapper,
            strategyRepository = strategyRepo,
            strategyPositionRepository = positionRepo,
            orderRequestRepositoryProvider = providerOf(orderRepo),
            kisOrderPort = kisOrderPort,
            orderCancelledKafkaProducer = cancelledProducer,
            orderIntentKafkaProducer = intentProducer,
            holdingPositionIndex = index,
            holdingDemandRegistrar = registrar,
            takeProfitLimitEnabled = tpEnabled,
        )

    private fun strategyEntity(takeProfitPct: String? = "5") =
        ConsumerStrategyJpaEntity(
            id = 100L,
            userId = 1L,
            name = "s",
            nameKo = null,
            dslEntry = "x",
            dslExit = null,
            takeProfitPct = takeProfitPct?.let { BigDecimal(it) },
        )

    private fun position(
        qty: Int = 10,
        avgPrice: String = "50000",
    ) = StrategyPositionJpaEntity(
        userId = "user-A",
        strategyId = 100L,
        code = "005930",
        quantity = qty,
        avgPrice = BigDecimal(avgPrice),
        updatedAt = Instant.parse("2026-05-22T01:00:00Z"),
    )

    private fun filledRecord(
        side: OrderSide = OrderSide.BUY,
        qty: Int = 10,
        price: String = "50000",
        source: OrderSource = OrderSource.AUTO,
        // ADR 0115 §P1-2 — default 는 실시간 WS 신규 체결(신선 executedAt + fillSource WS) → TP 정상 발행.
        fillSource: String = "WS",
        executedAtEpoch: Long = Instant.now().epochSecond,
    ): ConsumerRecord<String, String> {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "orderId" to 9L,
                    "signalId" to 1L,
                    "strategyId" to 100L,
                    "userId" to "user-A",
                    "code" to "005930",
                    "side" to side.name,
                    "executedQty" to qty,
                    "executedPrice" to price,
                    "executedAt" to executedAtEpoch,
                    "fillSource" to fillSource,
                    "source" to source.name,
                ),
            )
        return ConsumerRecord(KafkaTopics.Order.FILLED, 0, 0L, "user-A", payload)
    }

    @Test
    fun `BUY 체결 — HOLDING 등록 + 인덱스 즉시 갱신 + TP LIMIT 선주문(auto-tp)`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position()
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val keySlot = slot<String>()
        val priceSlot = slot<BigDecimal>()

        coordinator().onOrderFilled(filledRecord())

        verify(exactly = 1) { registrar.releasePendingBuy(9L, "005930") }
        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(exactly = 1) { index.onFillRefresh("005930") }
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.LIMIT,
                quantity = 10,
                limitPrice = capture(priceSlot),
                priceAtSignal = BigDecimal("50000"),
                idempotencyKey = capture(keySlot),
                orderTrigger = any(),
                triggerRefId = any(),
                signalId = 1L,
            )
        }
        // 50,000 × 1.05 = 52,500 → 50,000~199,999 구간 100원 단위 정렬.
        assertThat(priceSlot.captured).isEqualByComparingTo("52500")
        assertThat(keySlot.captured).startsWith("auto-tp:")
    }

    @Test
    fun `scale-in 평단 변동 — 기존 auto-tp 취소 후 재발행`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        // 추가 체결로 평단 60,000·수량 20으로 변동.
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 20, avgPrice = "60000")
        every { orderRepo.findOpenByUserId("user-A") } returns
            listOf(openTp(qty = 10, limitPrice = "52500")) // 옛 TP(평단 50,000 기준)
        every { kisOrderPort.cancel(any(), any()) } returns OrderResult.Right(Unit)

        coordinator().onOrderFilled(filledRecord(qty = 10, price = "70000"))

        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 1) { cancelledProducer.publish(any()) }
        // 새 평단 60,000 × 1.05 = 63,000 → 50,000~199,999 100원 단위.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                OrderPriceType.LIMIT,
                20,
                match {
                    it!!.compareTo(BigDecimal("63000")) ==
                        0
                },
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `기존 TP가 이미 정합(동일 qty·price) — 취소·재발행 0`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 10, avgPrice = "50000")
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openTp(qty = 10, limitPrice = "52500"))

        coordinator().onOrderFilled(filledRecord())

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `takeProfitPct 미설정 — TP 선주문 0 (HOLDING 등록은 수행)`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity(takeProfitPct = null))

        coordinator().onOrderFilled(filledRecord())

        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `take-profit-limit-enabled=false — TP 0 (구독은 유지)`() {
        coordinator(tpEnabled = false).onOrderFilled(filledRecord())
        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `SELL 전량 청산 — HOLDING release`() {
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 0)
        every { index.heldBy("005930") } returns emptyList()

        coordinator().onOrderFilled(filledRecord(side = OrderSide.SELL))

        verify(exactly = 1) { index.onFillRefresh("005930") }
        verify(exactly = 1) { registrar.onFullyExited("005930") }
    }

    @Test
    fun `SELL 부분 청산(잔량 남음) — release 0`() {
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 5)

        coordinator().onOrderFilled(filledRecord(side = OrderSide.SELL))

        verify(exactly = 1) { index.onFillRefresh("005930") }
        verify(exactly = 0) { registrar.onFullyExited(any()) }
    }

    // =====================================================================
    // ADR 0114 R3 시나리오 4 — 분할매수 TP 재정렬 (사용자 직접 지정 숫자 검증 ★)
    // 익절 +3% 전략. 1차 BUY 10@100,000 → TP 10@103,000. 2차 BUY 10@95,000(평단 97,500)
    //   → 기존 TP 취소 + 신규 TP 20@100,400(=floor(97,500×1.03)=floor(100,425)). 부분체결/정합 변형 포함.
    // =====================================================================

    @Test
    fun `R3 시나리오4-A — 1차 BUY 10주@100,000 → auto-tp LIMIT 10주@103,000 발행`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity(takeProfitPct = "3"))
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns
            position(qty = 10, avgPrice = "100000")
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val priceSlot = slot<BigDecimal>()
        val qtySlot = slot<Int>()

        coordinator().onOrderFilled(filledRecord(qty = 10, price = "100000"))

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.LIMIT,
                quantity = capture(qtySlot),
                limitPrice = capture(priceSlot),
                priceAtSignal = BigDecimal("100000"),
                idempotencyKey = any(),
                orderTrigger = any(),
                triggerRefId = any(),
                signalId = any(),
            )
        }
        // 100,000 × 1.03 = 103,000 → 50,000~199,999 구간 100원 단위 → 103,000 그대로.
        assertThat(qtySlot.captured).isEqualTo(10)
        assertThat(priceSlot.captured).isEqualByComparingTo("103000")
    }

    @Test
    fun `R3 시나리오4-B — 2차 BUY 10주@95,000(평단 97,500) → 기존 TP 취소 + 신규 TP 20주@100,400`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity(takeProfitPct = "3"))
        // 2차 체결 반영 후 포지션 — 평단 97,500·수량 20.
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns
            position(qty = 20, avgPrice = "97500")
        // 기존 TP(1차 기준 10주@103,000)가 open 상태.
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openTp(qty = 10, limitPrice = "103000"))
        every { kisOrderPort.cancel(any(), any()) } returns OrderResult.Right(Unit)
        val priceSlot = slot<BigDecimal>()
        val qtySlot = slot<Int>()

        coordinator().onOrderFilled(filledRecord(qty = 10, price = "95000"))

        // 기존 TP 취소 1건.
        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 1) { cancelledProducer.publish(any()) }
        // 신규 TP — 평단 97,500 × 1.03 = 100,425 → 호가단위 100원 내림 = 100,400. 수량 20.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.LIMIT,
                quantity = capture(qtySlot),
                limitPrice = capture(priceSlot),
                priceAtSignal = BigDecimal("97500"),
                idempotencyKey = any(),
                orderTrigger = any(),
                triggerRefId = any(),
                signalId = any(),
            )
        }
        assertThat(qtySlot.captured).isEqualTo(20)
        assertThat(priceSlot.captured).isEqualByComparingTo("100400")
    }

    @Test
    fun `R3 시나리오4-C — 2차 부분체결(10중 4주 = 14주 평단 98,571_428571) → 신규 TP 14주@101,500`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity(takeProfitPct = "3"))
        // 부분체결 4주 반영 후 — 수량 14, 평단 (10×100,000+4×95,000)/14 = 98,571.428571.
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns
            position(qty = 14, avgPrice = "98571.428571")
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openTp(qty = 10, limitPrice = "103000"))
        every { kisOrderPort.cancel(any(), any()) } returns OrderResult.Right(Unit)
        val priceSlot = slot<BigDecimal>()
        val qtySlot = slot<Int>()

        coordinator().onOrderFilled(filledRecord(qty = 4, price = "95000"))

        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        // 98,571.428571 × 1.03 = 101,528.57… → 호가단위 100원 내림 = 101,500. 수량 14.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                OrderPriceType.LIMIT,
                capture(qtySlot),
                capture(priceSlot),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
        assertThat(qtySlot.captured).isEqualTo(14)
        assertThat(priceSlot.captured).isEqualByComparingTo("101500")
    }

    @Test
    fun `R3 시나리오4-D — 평단 변동 없음(정합) → 취소·재발행 0`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity(takeProfitPct = "3"))
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns
            position(qty = 10, avgPrice = "100000")
        // 이미 평단 100,000·10주 기준 정합 TP(10@103,000)가 open.
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openTp(qty = 10, limitPrice = "103000"))

        coordinator().onOrderFilled(filledRecord(qty = 10, price = "100000"))

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `MANUAL 체결 — 무시(TP·HOLDING 0)`() {
        coordinator().onOrderFilled(filledRecord(source = OrderSource.MANUAL))
        verify(exactly = 0) { registrar.onBuyFilled(any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── ADR 0115 §P1-2 replay 가드 ─────────────────────────────────────────

    @Test
    fun `P1-2 replay — fillSource REST_BACKFILL BUY 체결은 TP 미발행(인덱스·구독은 유지)`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position()
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()

        coordinator().onOrderFilled(filledRecord(fillSource = "REST_BACKFILL"))

        // 보유 인덱스·구독 갱신은 유지(청산 의사결정만 skip).
        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(exactly = 1) { index.onFillRefresh("005930") }
        // TP 선주문은 미발행(replay 중복 익절 차단).
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1-2 replay — executedAt이 신선도 임계 초과 과거(WS source여도) TP 미발행`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position()
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()

        // 1시간 전 체결 — 신선도 120초 초과 → 복구 체결로 보고 skip.
        val staleEpoch = Instant.now().epochSecond - 3_600
        coordinator().onOrderFilled(filledRecord(fillSource = "WS", executedAtEpoch = staleEpoch))

        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1-2 — 신선한 WS 체결은 정상 TP 발행(가드 false negative 0)`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position()
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()

        coordinator().onOrderFilled(filledRecord(fillSource = "WS")) // default executedAt = now (신선)

        verify(
            exactly = 1,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun openTp(
        qty: Int,
        limitPrice: String,
        ackPresent: Boolean = true,
    ): OrderRequest =
        OrderRequest(
            id = 7L,
            userId = "user-A",
            signalId = 0L,
            strategyId = 100L,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = qty,
            limitPrice = BigDecimal(limitPrice),
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0001234567" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = "auto-tp:old",
            requestedAt = Instant.parse("2026-05-22T01:00:00Z"),
            source = OrderSource.AUTO,
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}
