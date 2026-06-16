package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [PositionTakeProfitTickWatcher] 단위 테스트 — canary D2 hotfix B 익절 tick 안전망.
 *
 * 커버: tick≥익절가 발사 / 익절가 미도달 미발사 / open auto-tp LIMIT 공존 시 미발사 / 보유 0 미발사 /
 *       토글 off 회귀 / idemKey 날짜 차원(auto-tpt prefix + KST date) / 유령 포지션 가드 / 익절가 산정.
 */
class PositionTakeProfitTickWatcherTest {
    private val objectMapper = ObjectMapper()
    private val index = mockk<HoldingPositionIndex>()
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)

    // position(KIS 권위) 잔량 가드. default 로 보유(quantity>0) 반환해 발사 테스트 보존.
    private val positionRepo =
        mockk<PositionJpaRepository>(relaxed = true).also {
            every { it.findByUserIdAndCodeAndSource(any(), any(), any()) } returns positionEntity(quantity = 10)
        }

    // 실제 공용 컴포넌트 사용(DRY — listener 와 동일 취소 경로 검증).
    private val restingSellOrderCanceller = RestingSellOrderCanceller(kisOrderPort, cancelledProducer)

    // 고정 KST 날짜(2026-06-12) — idemKey 결정론.
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    private fun watcher(enabled: Boolean = true) =
        PositionTakeProfitTickWatcher(
            objectMapper = objectMapper,
            holdingPositionIndex = index,
            orderRequestRepositoryProvider = providerOf(orderRepo),
            restingSellOrderCanceller = restingSellOrderCanceller,
            orderIntentKafkaProducer = intentProducer,
            positionJpaRepository = positionRepo,
            enabled = enabled,
            clock = fixedClock,
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
        takeProfitPct: String? = "2",
        qty: Int = 10,
        strategyId: Long = 100L,
        code: String = "005930",
    ) = HoldingPositionIndex.HeldPosition(
        userId = "user-A",
        strategyId = strategyId,
        code = code,
        quantity = qty,
        avgPrice = BigDecimal(avgPrice),
        stopLossPct = BigDecimal("3"),
        takeProfitPct = takeProfitPct?.let { BigDecimal(it) },
        updatedAtEpoch = 1_700_000_000L,
    )

    private fun tickRecord(
        code: String = "005930",
        price: String = "51000",
        stale: Boolean = false,
    ): ConsumerRecord<String, String> {
        val payload = objectMapper.writeValueAsString(mapOf("code" to code, "price" to price, "stale" to stale))
        return ConsumerRecord(KafkaTopics.Market.TICK, 0, 0L, code, payload)
    }

    @Test
    fun `tick 익절가 도달 + open SELL 없음 — MARKET intent (auto-tpt 키 + KST 날짜)`() {
        // 평단 50,000 × (1+2%) = 51,000 → tick 51,000 ≥ target → 발동.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", takeProfitPct = "2"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val keySlot = slot<String>()
        watcher().onTick(tickRecord(price = "51000"))

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
                priceAtSignal = BigDecimal("51000"),
                idempotencyKey = capture(keySlot),
                orderTrigger = any(),
                triggerRefId = any(),
                signalId = any(),
            )
        }
        // F1 회피 — auto-tpt prefix(고정 해시 손절 키와 키 공간 격리) + KST 날짜 차원.
        assertThat(keySlot.captured).startsWith("auto-tpt:")
    }

    @Test
    fun `익절가 초과(11퍼센트 급등) — 발동 (시그널 의존 없이 tick 만으로)`() {
        // D2 결함 시나리오 — DSL exit 불성립이어도 tick 이 익절가 이상이면 watcher 가 발사.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", takeProfitPct = "2"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        watcher().onTick(tickRecord(price = "55500")) // +11%
        verify(
            exactly = 1,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `익절가 미도달 — 주문 0`() {
        // 평단 50,000 × (1+2%) = 51,000 → tick 50,900 < target → 미발동.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", takeProfitPct = "2"))
        watcher().onTick(tickRecord(price = "50900"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `체결 직후 TP LIMIT 공존(open auto-tp) — watcher 미발사 (중복 매도 방지)`() {
        // AutoExitOrderCoordinator 가 건 auto-tp open SELL LIMIT 이 살아 있으면 watcher 는 MARKET 발사하지 않는다.
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(idempotencyKey = "auto-tp:xyz"))
        watcher().onTick(tickRecord(price = "51000"))
        // OwnDuplicate → canProceed=false → 미발사. 취소도 0(LIMIT 보존).
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `보유 0 (인덱스 miss) — 평가 0 (DB 조회 0)`() {
        every { index.heldBy("005930") } returns emptyList()
        watcher().onTick(tickRecord())
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRepo.findOpenByUserId(any()) }
    }

    @Test
    fun `토글 off — no-op 회귀 (현행 시그널 의존 익절로 복귀)`() {
        watcher(enabled = false).onTick(tickRecord(price = "55000"))
        verify(exactly = 0) { index.heldBy(any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `idemKey 날짜 차원 — 같은 KST 일자 연속 tick 은 동일 키(하루 1회 한정)`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", takeProfitPct = "2"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val keys = mutableListOf<String>()
        every {
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
                capture(keys),
                any(),
                any(),
                any(),
            )
        } returns Unit

        repeat(3) { watcher().onTick(tickRecord(price = "51000")) }

        // OrderRouter idem:order:{key} 가 동일 키 재발행을 흡수하도록, 같은 날·같은 상태는 동일 키여야 한다(폭주 방지).
        assertThat(keys).hasSize(3)
        assertThat(keys.toSet()).hasSize(1)
        assertThat(keys.first()).startsWith("auto-tpt:")
    }

    @Test
    fun `idemKey 날짜 차원 — 날짜가 다르면 키가 달라진다 (F1 영구 차단 회피)`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", takeProfitPct = "2"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()

        val day1Key = slot<String>()
        val w1 =
            PositionTakeProfitTickWatcher(
                objectMapper,
                index,
                providerOf(orderRepo),
                restingSellOrderCanceller,
                intentProducer,
                positionRepo,
                enabled = true,
                magicjarMode = "MOCK",
                clock = Clock.fixed(Instant.parse("2026-06-12T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            )
        every {
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
                capture(day1Key),
                any(),
                any(),
                any(),
            )
        } returns Unit
        w1.onTick(tickRecord(price = "51000"))
        val key1 = day1Key.captured

        val day2Key = slot<String>()
        val w2 =
            PositionTakeProfitTickWatcher(
                objectMapper,
                index,
                providerOf(orderRepo),
                restingSellOrderCanceller,
                intentProducer,
                positionRepo,
                enabled = true,
                magicjarMode = "MOCK",
                clock = Clock.fixed(Instant.parse("2026-06-13T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            )
        every {
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
                capture(day2Key),
                any(),
                any(),
                any(),
            )
        } returns Unit
        w2.onTick(tickRecord(price = "51000"))
        val key2 = day2Key.captured

        // 같은 상태라도 KST 날짜가 다르면 키가 달라 다음 날 재발사가 가능하다(REJECTED 후 영구 차단 0).
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `유령 포지션 (position 권위 잔량 0) — 익절 미발사`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        every { positionRepo.findByUserIdAndCodeAndSource("user-A", "005930", "MOCK") } returns positionEntity(quantity = 0)
        watcher().onTick(tickRecord(price = "51000"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `수동 SELL 존재 — 익절 중단 (취소 0 · 발화 0)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(source = OrderSource.MANUAL))
        watcher().onTick(tickRecord(price = "51000"))
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `익절 미설정(takeProfitPct null) — 발화 0`() {
        every { index.heldBy("005930") } returns listOf(held(takeProfitPct = null))
        watcher().onTick(tickRecord(price = "99999"))
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
    fun `takeProfitPriceOf — 평단 50000 2퍼센트 = 51000 (호가단위 정렬)`() {
        val target = PositionTakeProfitTickWatcher.takeProfitPriceOf(BigDecimal("50000"), BigDecimal("2"))
        assertThat(target).isEqualByComparingTo("51000")
    }

    @Test
    fun `takeProfitPriceOf — 비정상 입력 null`() {
        assertThat(PositionTakeProfitTickWatcher.takeProfitPriceOf(BigDecimal.ZERO, BigDecimal("2"))).isNull()
        assertThat(PositionTakeProfitTickWatcher.takeProfitPriceOf(BigDecimal("50000"), BigDecimal.ZERO)).isNull()
    }

    private fun openSell(
        idempotencyKey: String = "auto-tp:xyz",
        source: OrderSource = OrderSource.AUTO,
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
            limitPrice = BigDecimal("51000"),
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = "0001234567",
            kisFwdgOrgno = "00950",
            idempotencyKey = idempotencyKey,
            requestedAt = Instant.parse("2026-06-12T01:00:00Z"),
            source = source,
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}
