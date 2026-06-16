package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
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
import kotlinx.datetime.Instant as KxInstant

/**
 * [PositionStopLossTickWatcher] 단위 테스트 — ADR 0114 R2-C2b ① tick 손절 안전망.
 *
 * 커버: 인덱스 hit/miss · 손절가 도달/미도달 · 이중 발화 멱등(auto-sl) · 수동 SELL 가드 · ACK 보류 · stop-price 산정.
 */
class PositionStopLossTickWatcherTest {
    private val objectMapper = ObjectMapper()
    private val index = mockk<HoldingPositionIndex>()
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)

    // ADR 0115 §P2-3 — position(KIS 권위) 잔량 가드. default 로 보유(quantity>0) 반환해 기존 손절 발사 테스트 보존.
    // hotfix 2026-06-11 (D1): source 차원 조회로 정합 — watcher 가 현재 모드(default MOCK) source 로 좁혀 읽는다.
    private val positionRepo =
        mockk<PositionJpaRepository>(relaxed = true).also {
            every { it.findByUserIdAndCodeAndSource(any(), any(), any()) } returns positionEntity(quantity = 10)
        }

    // 실제 공용 컴포넌트를 그대로 사용(DRY — listener와 동일 취소 경로 검증). cancel 검증은 wrapped kisOrderPort로 유지.
    private val restingSellOrderCanceller = RestingSellOrderCanceller(kisOrderPort, cancelledProducer)

    private fun watcher(
        enabled: Boolean = true,
        tickCooldownSeconds: Long = 30,
    ) = PositionStopLossTickWatcher(
        objectMapper = objectMapper,
        holdingPositionIndex = index,
        orderRequestRepositoryProvider = providerOf(orderRepo),
        restingSellOrderCanceller = restingSellOrderCanceller,
        orderIntentKafkaProducer = intentProducer,
        positionJpaRepository = positionRepo,
        enabled = enabled,
        tickCooldownSeconds = tickCooldownSeconds,
    )

    private fun positionEntity(quantity: Int) =
        PositionJpaEntity(
            userId = "user-A",
            code = "005930",
            source = "MOCK",
            quantity = quantity,
            avgPrice = BigDecimal("50000"),
        )

    private fun held(
        avgPrice: String = "50000",
        stopLossPct: String? = "3",
        qty: Int = 10,
        strategyId: Long = 100L,
        code: String = "005930",
    ) = HoldingPositionIndex.HeldPosition(
        userId = "user-A",
        strategyId = strategyId,
        code = code,
        quantity = qty,
        avgPrice = BigDecimal(avgPrice),
        stopLossPct = stopLossPct?.let { BigDecimal(it) },
        takeProfitPct = BigDecimal("5"),
        updatedAtEpoch = 1_700_000_000L,
    )

    private fun tickRecord(
        code: String = "005930",
        price: String = "48000",
        stale: Boolean = false,
    ): ConsumerRecord<String, String> {
        val payload = objectMapper.writeValueAsString(mapOf("code" to code, "price" to price, "stale" to stale))
        return ConsumerRecord(KafkaTopics.Market.TICK, 0, 0L, code, payload)
    }

    @Test
    fun `인덱스 miss — 평가 0 (DB 조회 0)`() {
        every { index.heldBy("005930") } returns emptyList()
        watcher().onTick(tickRecord())
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRepo.findOpenByUserId(any()) }
    }

    @Test
    fun `손절가 미도달 — 주문 0`() {
        // 평단 50,000 × (1-3%) = 48,500 → tick 49,000 > stop → 미발동.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        watcher().onTick(tickRecord(price = "49000"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `손절가 도달 + open SELL 없음 — MARKET intent (auto-sl 키)`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val keySlot = slot<String>()
        watcher().onTick(tickRecord(price = "48000")) // 48,000 ≤ 48,500 stop

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 10,
                limitPrice = null,
                priceAtSignal = BigDecimal("48000"),
                idempotencyKey = capture(keySlot),
                orderTrigger = any(),
                triggerRefId = any(),
                signalId = any(),
            )
        }
        assertThat(keySlot.captured).startsWith("auto-sl:")
    }

    @Test
    fun `D1 회귀 — MOCK·REAL position 행 공존 시 현재 모드(source) 행만 조회해 단일 결과 (2 results 예외 0)`() {
        // 라이브 09:09 evidence: position UQ=(user,code,source) 라 005930 에 MOCK 0주 + REAL 1주 행 공존.
        // 기존 findByUserIdAndCode(source 무차원)는 2행을 받아 "2 results" 예외 → fail-safe 발사로 빠졌다.
        // 본 테스트는 source 차원 조회 stub 만 둔다 — source 무차원 경로를 타면 stub 미스로 발화 0 이 되어 실패한다.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        // default 모드(MOCK) source 로 조회 — MOCK 행(보유 가정) 단일 반환. REAL 행은 별도 source 키라 섞이지 않는다.
        every { positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK") } returns positionEntity(quantity = 1)

        watcher().onTick(tickRecord(price = "48000")) // 48,000 ≤ 48,500 stop → 발동

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
        // source 무차원 조회는 호출되지 않아야 한다(2 results 경로 폐지 증명).
        verify(exactly = 0) { positionRepo.findByUserIdAndCode(any(), any()) }
    }

    @Test
    fun `이미 open auto-sl SELL 존재 — 중복 발화 0 (멱등)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(idempotencyKey = "auto-sl:abc"))
        watcher().onTick(tickRecord(price = "48000"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `수동 SELL 존재 — 손절 중단 (취소 0 · 발화 0)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(source = OrderSource.MANUAL))
        watcher().onTick(tickRecord(price = "48000"))
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AUTO 같은 전략 SELL(익절) 존재 + ACK 확정 — 취소 후 손절 발화`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns
            listOf(openSell(idempotencyKey = "auto-tp:xyz", source = OrderSource.AUTO))
        every { kisOrderPort.cancel(any(), any()) } returns OrderResult.Right(Unit)
        watcher().onTick(tickRecord(price = "48000"))
        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 1,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AUTO 같은 전략 SELL ACK 미확정 — 취소 보류 (손절 발화 0)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns
            listOf(openSell(idempotencyKey = "auto-tp:xyz", source = OrderSource.AUTO, ackPresent = false))
        watcher().onTick(tickRecord(price = "48000"))
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stale tick — 평가 0`() {
        watcher().onTick(tickRecord(stale = true))
        verify(exactly = 0) { index.heldBy(any()) }
    }

    @Test
    fun `fallback polling 가격은 stale이어도 보유 손절 안전망을 평가한다`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()

        watcher().onFallbackPriceTick(
            TickEvent(
                code = "005930",
                price = BigDecimal("48000"),
                volume = 0L,
                ts = KxInstant.parse("2026-06-16T01:00:00Z"),
                source = PositionStopLossTickWatcher.SOURCE_KIS_POLLING,
                stale = true,
            ),
        )

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 10,
                limitPrice = null,
                priceAtSignal = BigDecimal("48000"),
                idempotencyKey = any(),
                orderTrigger = any(),
                triggerRefId = any(),
                signalId = any(),
            )
        }
    }

    @Test
    fun `손절 미설정(stopLossPct null) — 발화 0`() {
        every { index.heldBy("005930") } returns listOf(held(stopLossPct = null))
        watcher().onTick(tickRecord(price = "1"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `R3 시나리오8 — watcher는 readiness·market-hours·전략status 게이트와 무관하게 tick만으로 발동`() {
        // tick watcher는 1m 경로의 4중 게이트(readiness·market-hours·status·캔들마감)를 일절 참조하지 않는다.
        // 입력은 index hit + tick 가격뿐 — readiness FAILED 상황(1m 경로 BUY/SELL 억제 중)에도 손절은 발동한다.
        // (1m 경로 readiness 억제는 RealtimeSignalGeneratorTest가 별도 커버 — 본 테스트는 watcher 우회 보장만.)
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        watcher().onTick(tickRecord(price = "48000")) // 48,000 ≤ 48,500 stop → 발동

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                OrderSide.SELL,
                OrderPriceType.MARKET,
                any(),
                null,
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `R3 시나리오1 — 손절 발동은 단일 MARKET SELL 멱등(1m 경로 auto-sl 존재 시 중복 0)`() {
        // 1m 경로가 이미 auto-sl SELL을 걸어둔 상태에서 tick이 손절가에 또 닿아도 tick watcher는 재발행하지 않는다.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(idempotencyKey = "auto-sl:1m-path"))
        watcher().onTick(tickRecord(price = "47000"))
        // 이미 open auto-sl 존재 → 중복 발화 0 (멱등 — 단일 MARKET SELL 보장).
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stopPriceOf — 평단 50000 3퍼센트 = 48500 (호가단위 정렬)`() {
        // 48,500 → 5,000~19,999 구간 10원 단위 → 48,500 그대로.
        val stop = PositionStopLossTickWatcher.stopPriceOf(BigDecimal("50000"), BigDecimal("3"))
        assertThat(stop).isEqualByComparingTo("48500")
    }

    @Test
    fun `stopPriceOf — 비정상 입력 null`() {
        assertThat(PositionStopLossTickWatcher.stopPriceOf(BigDecimal.ZERO, BigDecimal("3"))).isNull()
        assertThat(PositionStopLossTickWatcher.stopPriceOf(BigDecimal("50000"), BigDecimal.ZERO)).isNull()
    }

    // ── ADR 0115 §P2-1 cooldown ───────────────────────────────────────────

    @Test
    fun `P2-1 cooldown — 같은 포지션 상태 연속 tick은 1회만 intent (재발행 0)`() {
        val pos = held(avgPrice = "50000", stopLossPct = "3")
        every { index.heldBy("005930") } returns listOf(pos)
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val w = watcher(tickCooldownSeconds = 30)

        // 매 tick 손절가 도달 — cooldown으로 1회만 발행.
        repeat(5) { w.onTick(tickRecord(price = "48000")) }

        verify(
            exactly = 1,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P2-1 cooldown — 손절가 미도달 복귀 시 cooldown 해제(다음 도달 즉시 발화)`() {
        val pos = held(avgPrice = "50000", stopLossPct = "3")
        every { index.heldBy("005930") } returns listOf(pos)
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val w = watcher(tickCooldownSeconds = 300)

        w.onTick(tickRecord(price = "48000")) // 발화 1
        w.onTick(tickRecord(price = "49000")) // 미도달 복귀 → cooldown clear
        w.onTick(tickRecord(price = "48000")) // 재도달 → 즉시 발화 2

        verify(
            exactly = 2,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P2-1 cooldown — 포지션 상태(updatedAtEpoch) 변화 시 즉시 재발화`() {
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val w = watcher(tickCooldownSeconds = 300)

        val posV1 = held(avgPrice = "50000", stopLossPct = "3").copy(updatedAtEpoch = 1_700_000_000L)
        every { index.heldBy("005930") } returns listOf(posV1)
        w.onTick(tickRecord(price = "48000")) // 발화 1

        // 부분 청산 등으로 상태(updatedAtEpoch) 변경 — cooldown 무시하고 즉시 발화.
        val posV2 = held(avgPrice = "50000", stopLossPct = "3").copy(updatedAtEpoch = 1_700_000_999L)
        every { index.heldBy("005930") } returns listOf(posV2)
        w.onTick(tickRecord(price = "48000")) // 발화 2

        verify(
            exactly = 2,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── ADR 0115 §P2-3 유령 포지션 가드 ────────────────────────────────────

    @Test
    fun `P2-3 — position(KIS 권위) 잔량 0(유령) 이면 손절 미발사`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        // strategy_position 인덱스엔 보유로 잡히지만 KIS position 권위 잔량 0 — 유령. (source 차원 조회 — default MOCK)
        every { positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK") } returns positionEntity(quantity = 0)

        watcher().onTick(tickRecord(price = "48000"))

        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P2-3 — position 권위 잔량 보유면 손절 발사 유지(PAUSED 전략도 실보유 보호)`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        every { positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK") } returns positionEntity(quantity = 10)

        watcher().onTick(tickRecord(price = "48000"))

        verify(
            exactly = 1,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun openSell(
        idempotencyKey: String = "auto-tp:xyz",
        source: OrderSource = OrderSource.AUTO,
        ackPresent: Boolean = true,
        strategyId: Long = 100L,
    ): OrderRequest =
        OrderRequest(
            id = 9L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = 10,
            limitPrice = BigDecimal("52000"),
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0001234567" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = idempotencyKey,
            requestedAt = Instant.parse("2026-05-22T01:00:00Z"),
            source = source,
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}
