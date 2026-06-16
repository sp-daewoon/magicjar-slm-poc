package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * [PositionViAutoExitWatcher] 단위 테스트 — H0UNMKO0 VI 정책 매트릭스.
 *
 * 커버: ALERT_ONLY 알림-only, EXIT_MARKET 시장가 청산, HOLD 무시.
 */
class PositionViAutoExitWatcherTest {
    private val objectMapper = ObjectMapper()
    private val index = mockk<HoldingPositionIndex>()
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)
    private val positionRepo =
        mockk<PositionJpaRepository>(relaxed = true).also {
            every { it.findByUserIdAndCodeAndSource(any(), any(), any()) } returns positionEntity(quantity = 10)
        }
    private val restingSellOrderCanceller = RestingSellOrderCanceller(kisOrderPort, cancelledProducer)

    private fun watcher() =
        PositionViAutoExitWatcher(
            objectMapper = objectMapper,
            holdingPositionIndex = index,
            orderRequestRepositoryProvider = providerOf(orderRepo),
            restingSellOrderCanceller = restingSellOrderCanceller,
            orderIntentKafkaProducer = intentProducer,
            positionJpaRepository = positionRepo,
            systemEventLogger = systemEventLogger,
            enabled = true,
            cooldownSeconds = 30,
            magicjarMode = "MOCK",
        )

    @Test
    fun `ALERT_ONLY — VI active면 알림만 남기고 주문은 발행하지 않는다`() {
        every { index.heldBy("005930") } returns listOf(held(ViActionPolicy.ALERT_ONLY))

        watcher().onMarketStatus(marketStatusRecord())

        verify(exactly = 1) { systemEventLogger.emit(any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRepo.findOpenByUserId(any()) }
    }

    @Test
    fun `EXIT_MARKET — VI active면 알림 후 MARKET SELL intent를 발행한다`() {
        every { index.heldBy("005930") } returns listOf(held(ViActionPolicy.EXIT_MARKET))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val triggerSlot = slot<OrderTrigger>()
        val keySlot = slot<String>()

        watcher().onMarketStatus(marketStatusRecord())

        verify(exactly = 1) { systemEventLogger.emit(any()) }
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 10,
                limitPrice = null,
                priceAtSignal = BigDecimal("50000"),
                idempotencyKey = capture(keySlot),
                orderTrigger = capture(triggerSlot),
                triggerRefId = 100L,
                signalId = any(),
            )
        }
        assertThat(triggerSlot.captured).isEqualTo(OrderTrigger.VI_AUTO_EXIT)
        assertThat(keySlot.captured).startsWith("auto-vi:")
    }

    @Test
    fun `HOLD — VI active여도 알림과 주문 모두 수행하지 않는다`() {
        every { index.heldBy("005930") } returns listOf(held(ViActionPolicy.HOLD))

        watcher().onMarketStatus(marketStatusRecord())

        verify(exactly = 0) { systemEventLogger.emit(any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun held(policy: ViActionPolicy) =
        HoldingPositionIndex.HeldPosition(
            userId = "user-A",
            strategyId = 100L,
            code = "005930",
            quantity = 10,
            avgPrice = BigDecimal("50000"),
            stopLossPct = BigDecimal("3"),
            takeProfitPct = BigDecimal("5"),
            updatedAtEpoch = 1_700_000_000L,
            viActionPolicy = policy,
        )

    private fun marketStatusRecord(): ConsumerRecord<String, String> {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "code" to "005930",
                    "isViActive" to true,
                    "viClassCode" to "1",
                    "overtimeViClassCode" to "0",
                    "exchangeClassCode" to "UN",
                    "sourceTrId" to "H0UNMKO0",
                    "ts" to "2026-06-13T09:10:00+09:00",
                ),
            )
        return ConsumerRecord(KafkaTopics.Market.STATUS, 0, 0L, "005930", payload)
    }

    private fun positionEntity(quantity: Int) =
        PositionJpaEntity(
            userId = "user-A",
            code = "005930",
            source = "MOCK",
            quantity = quantity,
            avgPrice = BigDecimal("50000"),
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}
