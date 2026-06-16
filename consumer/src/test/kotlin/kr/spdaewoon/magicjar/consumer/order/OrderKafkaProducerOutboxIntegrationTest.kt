package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlinx.datetime.Instant as KxInstant

/**
 * 4 producer outbox 통합 검증 (ADR 0084 P1-1 + spec §2-5).
 *
 * 검증 매트릭스:
 *   (a) OrderRequestedKafkaProducer → outbox.save(ORDER_REQUESTED, order.requested.v1) + markPublished
 *   (b) OrderRejectedKafkaProducer  → outbox.save(ORDER_REJECTED, order.rejected.v1) + markPublished
 *   (c) OrderFilledKafkaProducer    → outbox.save(ORDER_FILLED, order.filled.v1) + markPublished
 *   (d) OrderIntentKafkaProducer    → outbox.save(ORDER_INTENT, order.intent.v1) + markPublished
 *
 * 모든 producer에서 publish 직전 outbox INSERT + publish 성공 시 markPublished 호출 확인.
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class OrderKafkaProducerOutboxIntegrationTest {
    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val outboxRepository = mockk<OrderEventOutboxRepository>(relaxed = true)
    private val provider = mockk<ObjectProvider<OrderEventOutboxRepository>>()

    // ADR 0094 P0-OPS-6 — PersistenceMetrics ObjectProvider (graceful skip for non-metric tests)
    private val metricsProvider = mockk<ObjectProvider<PersistenceMetrics>>(relaxed = true)

    init {
        every { provider.getIfAvailable() } returns outboxRepository
        every { metricsProvider.getIfAvailable() } returns null
        every { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) } returns
            CompletableFuture.completedFuture(mockk(relaxed = true))
    }

    private fun sampleRequest(): OrderRequest =
        OrderRequest(
            id = 99L,
            userId = "U1",
            signalId = 1L,
            strategyId = 1L,
            code = "005930",
            side = OrderSide.BUY,
            priceType = OrderPriceType.LIMIT,
            quantity = 10,
            limitPrice = BigDecimal("70000"),
            status = OrderStatus.REQUESTED,
            mode = TradingMode.MOCK,
            idempotencyKey = "idem-1",
            requestedAt = Instant.now(),
        )

    @Test
    fun `(a) OrderRequestedKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 1001L)
        }

        val producer = OrderRequestedKafkaProducer(kafkaTemplate, objectMapper, provider)
        producer.publish(sampleRequest())

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send(KafkaTopics.Order.REQUESTED, "U1", any<String>()) }
        verify { outboxRepository.markPublished(1001L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_REQUESTED)
        assertThat(outboxSlot.captured.topic).isEqualTo(KafkaTopics.Order.REQUESTED)
        assertThat(outboxSlot.captured.partitionKey).isEqualTo("U1")
        assertThat(outboxSlot.captured.payloadJson).contains("\"userId\":\"U1\"")
    }

    @Test
    fun `(b) OrderRejectedKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 2002L)
        }

        val producer = OrderRejectedKafkaProducer(kafkaTemplate, objectMapper, provider)
        producer.publish(sampleRequest(), reason = "REAL_GATE_DENIED:test")

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send(KafkaTopics.Order.REJECTED, "U1", any<String>()) }
        verify { outboxRepository.markPublished(2002L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_REJECTED)
        assertThat(outboxSlot.captured.topic).isEqualTo(KafkaTopics.Order.REJECTED)
        assertThat(outboxSlot.captured.payloadJson).contains("REAL_GATE_DENIED:test")
    }

    @Test
    fun `(c) OrderFilledKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 3003L)
        }

        val producer = OrderFilledKafkaProducer(kafkaTemplate, objectMapper, provider)
        val execution =
            OrderExecution(
                id = 555L,
                orderId = 99L,
                userId = "U1",
                kisOrderNo = "0000000001",
                executedQuantity = 10,
                executedPrice = BigDecimal("70000"),
                executedAt = Instant.now(),
                eventId = "0000000001:090000:10",
                createdAt = Instant.now(),
            )
        producer.publish(sampleRequest(), execution, partial = false)

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send(KafkaTopics.Order.FILLED, "U1", any<String>()) }
        verify { outboxRepository.markPublished(3003L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_FILLED)
        assertThat(outboxSlot.captured.topic).isEqualTo(KafkaTopics.Order.FILLED)

        // hotfix(2026-05-20): STOMP contract key 정합 가드
        // — frontend useStompReconnect.ts L104 `qty ?? executedQty` path 정합
        // — StompOrderNotifier.toStompPayload raw["quantity"]·raw["executedQty"] read
        val payload = outboxSlot.captured.payloadJson
        assertThat(payload).contains("\"quantity\":10")
        assertThat(payload).contains("\"executedQty\":10")
        assertThat(payload).doesNotContain("\"executedQuantity\":") // 옛 key 회귀 가드
    }

    @Test
    fun `(c-2) OrderRejectedKafkaProducer — STOMP contract key 정합 (rejectReason + quantity)`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 2999L)
        }

        val producer = OrderRejectedKafkaProducer(kafkaTemplate, objectMapper, provider)
        producer.publish(sampleRequest(), reason = "REAL_GATE_DENIED:cond")

        val payload = outboxSlot.captured.payloadJson
        // hotfix(2026-05-20): STOMP contract key 정합 — raw["rejectReason"]·raw["quantity"]
        assertThat(payload).contains("\"quantity\":10")
        assertThat(payload).contains("\"rejectReason\":\"REAL_GATE_DENIED:cond\"")
        assertThat(payload).doesNotContain("\"reason\":\"") // 옛 key 회귀 가드
    }

    @Test
    fun `(d) OrderIntentKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 4004L)
        }

        val signal =
            Signal(
                id = 77L,
                strategyId = 1L,
                userId = "U1",
                code = "005930",
                interval = CandleInterval.FIVE_MIN,
                ts = KxInstant.fromEpochSeconds(1_736_640_000L),
                side = SignalSide.BUY,
                strength = 75,
                reason = "test",
                priceAtSignal = BigDecimal("70000"),
            )

        val producer = OrderIntentKafkaProducer(kafkaTemplate, objectMapper, provider, metricsProvider)
        producer.publish(signal)

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send(KafkaTopics.Order.INTENT, "U1", any<String>()) }
        verify { outboxRepository.markPublished(4004L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_INTENT)
        assertThat(outboxSlot.captured.topic).isEqualTo(KafkaTopics.Order.INTENT)
    }

    @Test
    fun `(c-3) OrderCancelledKafkaProducer — STOMP contract key 정합 (quantity 포함)`() {
        // OrderCancelledKafkaProducer는 outbox 미사용 — direct publish only.
        // payload key contract만 검증한다.
        val payloadSlot = slot<String>()
        every { kafkaTemplate.send(KafkaTopics.Order.CANCELLED, "U1", capture(payloadSlot)) } answers {
            mockk(relaxed = true)
        }

        val producer = OrderCancelledKafkaProducer(kafkaTemplate, objectMapper)
        producer.publish(sampleRequest())

        verify { kafkaTemplate.send(KafkaTopics.Order.CANCELLED, "U1", any<String>()) }
        // hotfix(2026-05-20): STOMP contract key 정합 — frontend "0주" toast 회피
        assertThat(payloadSlot.captured).contains("\"quantity\":10")
    }

    @Test
    fun `(e) outbox provider 미등록 — direct publish fallback`() {
        val emptyProvider = mockk<ObjectProvider<OrderEventOutboxRepository>>()
        every { emptyProvider.getIfAvailable() } returns null

        val producer = OrderRequestedKafkaProducer(kafkaTemplate, objectMapper, emptyProvider)
        producer.publish(sampleRequest())

        verify(exactly = 0) { outboxRepository.save(any()) }
        verify { kafkaTemplate.send(KafkaTopics.Order.REQUESTED, "U1", any<String>()) }
    }
}
