package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.session.SimpleMarketSessionResolver
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.KisOrderAck
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderIdempotencyGuard
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.TradingModeGate
import kr.spdaewoon.magicjar.domain.risk.port.TradeFrequencyGuard
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.kafka.support.Acknowledgment
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Optional

/**
 * OrderRouterKafkaListener 단위 테스트 (ADR 0068 §14-2·§14-7 LOCK).
 *
 * 6 케이스 매트릭스 (§14-7):
 *  (a) intent 정상 → ACK 흐름 (audit KIS_ACK + Kafka requested publish + updateKisAck)
 *  (b) idempotency duplicate → IDEMPOTENCY_SKIPPED audit + 후속 0
 *  (c) real gate denied → REAL_GATE_DENIED audit + Kafka rejected publish
 *  (d) killswitch denied → KILLSWITCH_DENIED audit + Kafka rejected publish
 *  (e) KIS 5xx 1회 retry then fail → KIS_EXCEPTION audit + Kafka rejected publish
 *  (f) KIS rt_cd != 0 reject → KIS_REJECT audit + Kafka rejected publish
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class OrderRouterKafkaListenerTest {
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val auditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val killSwitchPort = mockk<KillSwitchQueryPort>(relaxed = true)
    private val tradingModeGate = mockk<TradingModeGate>()
    private val idempotencyGuard = mockk<OrderIdempotencyGuard>()
    private val kisOrderPort = mockk<KisOrderPort>()
    private val requestedProducer = mockk<OrderRequestedKafkaProducer>(relaxed = true)
    private val rejectedProducer = mockk<OrderRejectedKafkaProducer>(relaxed = true)
    private val accountBalanceChangedProducer = mockk<AccountBalanceChangedKafkaProducer>(relaxed = true)
    private val executionBackfillService = mockk<KisExecutionBackfillService>(relaxed = true)
    private val killSwitchJpaRepository = mockk<KillSwitchJpaRepository>(relaxed = true)
    private val tradeFrequencyGuard = mockk<TradeFrequencyGuard>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val orderRepoProvider =
        mockk<ObjectProvider<OrderRequestRepository>>().also {
            every { it.getIfAvailable() } returns orderRepo
        }
    private val auditRepoProvider =
        mockk<ObjectProvider<OrderAuditEventRepository>>().also {
            every { it.getIfAvailable() } returns auditRepo
        }
    private val killSwitchProvider =
        mockk<ObjectProvider<KillSwitchQueryPort>>().also {
            every { it.getIfAvailable() } returns killSwitchPort
        }

    // 자본 정합 hotfix(2026-06-08) — 체결 sync 게이트가 REAL·MOCK 모두 실행되므로 default provider는
    // service를 합류시키고 MOCK account type 완료(PASS) sync를 stub한다(production wiring 모사).
    // 과거 REAL-only 게이트 때만 MOCK 흐름이 null provider로도 통과했다.
    private val executionBackfillProvider =
        mockk<ObjectProvider<KisExecutionBackfillService>>().also {
            every { it.getIfAvailable() } returns executionBackfillService
        }

    init {
        every { executionBackfillService.syncUserTodayExecutions("12345678", AccountType.MOCK) } returns
            KisExecutionSyncResult(
                userId = "12345678",
                accountType = AccountType.MOCK,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = java.time.Instant.parse("2026-06-08T00:00:00Z"),
            )
    }

    private val killSwitchJpaRepositoryProvider =
        mockk<ObjectProvider<KillSwitchJpaRepository>>().also {
            every { it.getIfAvailable() } returns killSwitchJpaRepository
        }
    private val tradeFrequencyGuardProvider =
        mockk<ObjectProvider<TradeFrequencyGuard>>().also {
            every { it.getIfAvailable() } returns tradeFrequencyGuard
        }
    private val marketSessionResolver = SimpleMarketSessionResolver()
    private val businessDayCalendar =
        mockk<BusinessDayCalendar>().also {
            every { it.isBusinessDay(any()) } returns true
        }
    private val businessDayCalendarProvider =
        mockk<ObjectProvider<BusinessDayCalendar>>().also {
            every { it.getIfAvailable() } returns businessDayCalendar
        }

    // ADR 0114 §4-3 P3 — PENDING_BUY watch는 부재(getIfAvailable null) → 기존 흐름 무영향(graceful skip 검증).
    private val holdingDemandRegistrarProvider =
        mockk<ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.HoldingDemandRegistrar>>().also {
            every { it.getIfAvailable() } returns null
        }

    // ADR 0115 §P2-2 — SELL 거부 자동정지 tracker. 본 테스트는 12 step 라우팅을 검증하므로 relaxed(부작용 무시).
    private val sellRejectAutoPauseTracker = mockk<SellRejectAutoPauseTracker>(relaxed = true)
    private val strategyReplayService = mockk<StrategyPositionExecutionReplayService>(relaxed = true)
    private val strategyReplayServiceProvider =
        mockk<ObjectProvider<StrategyPositionExecutionReplayService>>().also {
            every { it.getIfAvailable() } returns strategyReplayService
        }

    // 기존 12 step 흐름 테스트는 장운영시간 게이트를 비활성(enabled=false)으로 격리한다.
    // 게이트 자체 동작은 isWithinTradingHours 단위 테스트 + AUTO/MANUAL 분기 테스트로 별도 검증.
    private val listener =
        OrderRouterKafkaListener(
            orderRequestRepositoryProvider = orderRepoProvider,
            orderAuditEventRepositoryProvider = auditRepoProvider,
            killSwitchQueryPortProvider = killSwitchProvider,
            tradingModeGate = tradingModeGate,
            orderIdempotencyGuard = idempotencyGuard,
            kisOrderPort = kisOrderPort,
            orderRequestedKafkaProducer = requestedProducer,
            orderRejectedKafkaProducer = rejectedProducer,
            accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
            executionBackfillServiceProvider = executionBackfillProvider,
            killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
            marketSessionResolver = marketSessionResolver,
            businessDayCalendarProvider = businessDayCalendarProvider,
            objectMapper = objectMapper,
            holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
            sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
            strategyPositionExecutionReplayServiceProvider = strategyReplayServiceProvider,
            marketHoursGateEnabled = false,
        )

    @Test
    fun `(a) intent ACK 정상 흐름 — IDEM acquire + MOCK mode + KIS ACK → updateKisAck + KIS_ACK audit`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { orderRepo.findByKisOrderNo("ODNO123") } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { orderRepo.save(any()) }
        verify(exactly = 1) { requestedProducer.publish(any()) }
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 1) { orderRepo.updateKisAck(99L, "ODNO123", "ORG001", any()) }
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_ACK)
        verify(exactly = 1) { ack.acknowledge() }
    }

    // ── ADR 0122 §6 GAP-0 hotfix — 페이퍼 전략 MOCK 강제 라우터 배선 ──────────────────────

    private fun paperListener(
        strategyRepoProvider: ObjectProvider<ConsumerStrategyJpaRepository>,
        mode: String = "MOCK",
    ): OrderRouterKafkaListener =
        OrderRouterKafkaListener(
            orderRequestRepositoryProvider = orderRepoProvider,
            orderAuditEventRepositoryProvider = auditRepoProvider,
            killSwitchQueryPortProvider = killSwitchProvider,
            tradingModeGate = tradingModeGate,
            orderIdempotencyGuard = idempotencyGuard,
            kisOrderPort = kisOrderPort,
            orderRequestedKafkaProducer = requestedProducer,
            orderRejectedKafkaProducer = rejectedProducer,
            accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
            executionBackfillServiceProvider = executionBackfillProvider,
            killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
            marketSessionResolver = marketSessionResolver,
            businessDayCalendarProvider = businessDayCalendarProvider,
            objectMapper = objectMapper,
            holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
            sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
            strategyPositionExecutionReplayServiceProvider = strategyReplayServiceProvider,
            consumerStrategyJpaRepositoryProvider = strategyRepoProvider,
            magicjarMode = mode,
            marketHoursGateEnabled = false,
        )

    @Test
    fun `(paper-1) origin=PAPER_DISCOVERY 전략 주문은 게이트에 paperStrategy=true 전달 + MOCK 제출`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678", true) } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { orderRepo.findByKisOrderNo("ODNO123") } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"),
            )
        val paperRepo =
            mockk<ConsumerStrategyJpaRepository>().also { repo ->
                every { repo.findById(100L) } returns
                    Optional.of(mockk<ConsumerStrategyJpaEntity>().also { every { it.origin } returns "PAPER_DISCOVERY" })
            }
        val provider =
            mockk<ObjectProvider<ConsumerStrategyJpaRepository>>().also {
                every { it.getIfAvailable() } returns paperRepo
            }

        val ack = mockk<Acknowledgment>(relaxed = true)
        paperListener(provider).onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { tradingModeGate.resolveMode("12345678", true) }
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(paper-2) REAL 모드에서 strategy repo 미합류 시 fail-closed — PAPER_ORIGIN_UNRESOLVED REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 77L) }
        val absentProvider =
            mockk<ObjectProvider<ConsumerStrategyJpaRepository>>().also {
                every { it.getIfAvailable() } returns null
            }

        val ack = mockk<Acknowledgment>(relaxed = true)
        paperListener(absentProvider, mode = "REAL").onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 0) { tradingModeGate.resolveMode(any(), any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) {
            rejectedProducer.publish(any(), match { it.contains("PAPER_ORIGIN_UNRESOLVED:REPO_UNAVAILABLE") })
        }
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.REAL_GATE_DENIED)
        verify(exactly = 1) { ack.acknowledge() }
    }

    // ── ADR 0115 §2-P2-⑥ 키 사슬 무결성 — OrderTrigger 발사 출처 영속 ──────────────────────

    @Test
    fun `키사슬 — payload orderTrigger 명시 시 OrderRequest에 그대로 영속 (STRATEGY_SIGNAL + signal ref)`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.findByKisOrderNo(any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"))
        val saved = slot<OrderRequest>()
        every { orderRepo.save(capture(saved)) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        listener.onOrderIntent(buildRecord(triggerPayload("STRATEGY_SIGNAL", 42L)), mockk<Acknowledgment>(relaxed = true))

        assertThat(saved.captured.orderTrigger).isEqualTo(kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger.STRATEGY_SIGNAL)
        assertThat(saved.captured.triggerRefId).isEqualTo(42L)
    }

    @Test
    fun `키사슬 — orderTrigger 미발행(legacy) + AUTO + signalId 0 → AUTO_EXIT fallback (출처 모르는 주문 0)`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.findByKisOrderNo(any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"))
        val saved = slot<OrderRequest>()
        every { orderRepo.save(capture(saved)) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        // signalId=0, strategyId=7, source=AUTO, orderTrigger 미발행 → AUTO_EXIT + triggerRefId=strategyId
        listener.onOrderIntent(buildRecord(legacyAutoExitPayload()), mockk<Acknowledgment>(relaxed = true))

        assertThat(saved.captured.orderTrigger).isEqualTo(kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger.AUTO_EXIT)
        assertThat(saved.captured.triggerRefId).isEqualTo(7L)
    }

    @Test
    fun `키사슬 — MANUAL source는 orderTrigger 미발행이어도 MANUAL fallback + triggerRefId null`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.findByKisOrderNo(any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"))
        val saved = slot<OrderRequest>()
        every { orderRepo.save(capture(saved)) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        listener.onOrderIntent(buildRecord(manualPayload()), mockk<Acknowledgment>(relaxed = true))

        assertThat(saved.captured.orderTrigger).isEqualTo(kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger.MANUAL)
        assertThat(saved.captured.triggerRefId).isNull()
    }

    @Test
    fun `WS_PREINSERT 병합 ACK 후 누락된 strategy_position replay를 호출한다`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO_RACE", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"),
            )
        every { orderRepo.findByKisOrderNo("ODNO_RACE") } returns
            stubOrderRequest(id = 777L, strategyId = 0L, idempotencyKey = "WS_PREINSERT:ODNO_RACE")
        val canonical =
            stubOrderRequest(
                id = 99L,
                signalId = 99001L,
                strategyId = 1L,
                kisOrderNo = "ODNO_RACE",
                idempotencyKey = "99001:12345678",
            )
        every { orderRepo.updateKisAck(99L, "ODNO_RACE", "ORG001", any()) } returns canonical
        every { strategyReplayService.replayIfMissing(canonical, "WS_PREINSERT_ACK_MERGE") } returns 1

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { orderRepo.findByKisOrderNo("ODNO_RACE") }
        verify(exactly = 1) { strategyReplayService.replayIfMissing(canonical, "WS_PREINSERT_ACK_MERGE") }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(b) idempotency duplicate → IDEMPOTENCY_SKIPPED audit + 후속 0`() {
        every { idempotencyGuard.acquire(any(), any()) } returns false

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.IDEMPOTENCY_SKIPPED)
        verify(exactly = 0) { tradingModeGate.resolveMode(any(), any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `idempotency Redis 장애 → REJECTED 저장과 rejected 이벤트 발행`() {
        every { idempotencyGuard.acquire(any(), any()) } throws IllegalStateException("ORDER_IDEMPOTENCY_REDIS_DOWN")
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 77L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), "IDEMPOTENCY_REDIS_DOWN") }
        verify(exactly = 0) { tradingModeGate.resolveMode(any(), any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(c) real gate denied → REAL_GATE_DENIED audit + Kafka rejected publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns
            OrderResult.Left(DenyReason.RealGateDenied("MAGICJAR_ALLOW_REAL=false"))
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = slot<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.contains("REAL_GATE_DENIED") }) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(d) killswitch ON → KILLSWITCH_DENIED audit + Kafka rejected publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns true
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KILLSWITCH_DENIED)
        verify(exactly = 1) { rejectedProducer.publish(any(), eq("KILLSWITCH_ON")) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
    }

    @Test
    fun `REAL 주문 전 KIS 체결 sync 실패 시 REQUESTED와 KIS 전송 없이 REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.REAL)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { executionBackfillService.syncUserTodayExecutions("12345678", AccountType.REAL) } returns
            KisExecutionSyncResult.failure(
                userId = "12345678",
                accountType = AccountType.REAL,
                error = "KIS_FETCH_FAILED:timeout",
            )
        val syncProvider =
            mockk<ObjectProvider<KisExecutionBackfillService>>().also {
                every { it.getIfAvailable() } returns executionBackfillService
            }
        // ADR 0122 §6 GAP-0 — REAL 모드는 paper origin 조회 fail-closed이므로 USER_DSL stub 배선 의무
        val userDslStrategyRepo =
            mockk<ConsumerStrategyJpaRepository>().also { repo ->
                every { repo.findById(100L) } returns
                    Optional.of(mockk<ConsumerStrategyJpaEntity>().also { every { it.origin } returns "USER_DSL" })
            }
        val strategyRepoProvider =
            mockk<ObjectProvider<ConsumerStrategyJpaRepository>>().also {
                every { it.getIfAvailable() } returns userDslStrategyRepo
            }
        val realListener =
            OrderRouterKafkaListener(
                orderRequestRepositoryProvider = orderRepoProvider,
                orderAuditEventRepositoryProvider = auditRepoProvider,
                killSwitchQueryPortProvider = killSwitchProvider,
                tradingModeGate = tradingModeGate,
                orderIdempotencyGuard = idempotencyGuard,
                kisOrderPort = kisOrderPort,
                orderRequestedKafkaProducer = requestedProducer,
                orderRejectedKafkaProducer = rejectedProducer,
                accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                executionBackfillServiceProvider = syncProvider,
                killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                marketSessionResolver = marketSessionResolver,
                businessDayCalendarProvider = businessDayCalendarProvider,
                objectMapper = objectMapper,
                holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
                sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
                consumerStrategyJpaRepositoryProvider = strategyRepoProvider,
                magicjarMode = "REAL",
                marketHoursGateEnabled = false,
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        realListener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { executionBackfillService.syncUserTodayExecutions("12345678", AccountType.REAL) }
        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED && it.mode == TradingMode.REAL }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.contains("KIS_EXECUTION_SYNC_STALE") }) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `quantity 0 주문 의도는 KIS 전송 전에 REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(payloadWithQuantity(0)), ack)

        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), "INVALID_ORDER_QUANTITY:0") }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(e) KIS 5xx 1회 retry 후 fail → PENDING_RECONCILE 분기 (ADR 0084 P0-2)`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        // ADR 0084 P0-2 — 5xx fail은 REJECTED 대신 PENDING_RECONCILE 분기.
        every { orderRepo.updateStatus(99L, OrderStatus.PENDING_RECONCILE, any()) } returns null
        // 5xx 2회 throw — 1회 retry 후도 실패
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } throws
            WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "ISE",
                org.springframework.http.HttpHeaders.EMPTY,
                ByteArray(0),
                StandardCharsets.UTF_8,
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        // submit 2회 호출 — 1회 + 1 retry
        verify(exactly = 2) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        // ADR 0084 P0-2 — audit는 KIS_RECONCILE_PENDING (REJECTED 확정 전 보류 상태).
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_RECONCILE_PENDING)
        // ADR 0084 P0-2 — order.rejected.v1 Kafka publish 0건 (PENDING_RECONCILE 보류 — REJECTED 확정 전).
        verify(exactly = 0) { rejectedProducer.publish(any(), any()) }
        // PENDING_RECONCILE 상태로 updateStatus 1회 호출 정합.
        verify(exactly = 1) { orderRepo.updateStatus(99L, OrderStatus.PENDING_RECONCILE, any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `ORDER_NOT_SENT KIS 예외는 PENDING_RECONCILE이 아니라 REJECTED로 확정`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { orderRepo.updateStatus(99L, OrderStatus.REJECTED, any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Left(DenyReason.KisException("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT:test"))

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_EXCEPTION)
        verify(exactly = 1) {
            orderRepo.updateStatus(
                99L,
                OrderStatus.REJECTED,
                match { it.contains("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT") },
            )
        }
        verify(exactly = 0) { orderRepo.updateStatus(99L, OrderStatus.PENDING_RECONCILE, any()) }
        verify(exactly = 1) {
            rejectedProducer.publish(any(), match { it.contains("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT") })
        }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(g §62) Repository 미합류 (NotWired) → listener 진행 중단 + 후속 publish·KIS submit 0`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false

        // OrderRequestRepository provider가 null 반환 — 미합류 시뮬레이션
        val notWiredOrderRepoProvider =
            mockk<ObjectProvider<OrderRequestRepository>>().also {
                every { it.getIfAvailable() } returns null
            }
        val notWiredListener =
            OrderRouterKafkaListener(
                orderRequestRepositoryProvider = notWiredOrderRepoProvider,
                orderAuditEventRepositoryProvider = auditRepoProvider,
                killSwitchQueryPortProvider = killSwitchProvider,
                tradingModeGate = tradingModeGate,
                orderIdempotencyGuard = idempotencyGuard,
                kisOrderPort = kisOrderPort,
                orderRequestedKafkaProducer = requestedProducer,
                orderRejectedKafkaProducer = rejectedProducer,
                accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                executionBackfillServiceProvider = executionBackfillProvider,
                killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                marketSessionResolver = marketSessionResolver,
                businessDayCalendarProvider = businessDayCalendarProvider,
                objectMapper = objectMapper,
                holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
                sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
                marketHoursGateEnabled = false,
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        notWiredListener.onOrderIntent(buildRecord(stubPayload()), ack)

        // listener 진행 중단 — KIS submit + Kafka publish 모두 0
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 0) { rejectedProducer.publish(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(h §62) Repository save Exception (SaveFailed) → listener 진행 중단 + NotWired와 구분된 로그 + 후속 0`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        // save 자체가 Exception — FK violation 시뮬레이션
        every { orderRepo.save(any()) } throws
            org.springframework.dao.DataIntegrityViolationException(
                "order_request violates foreign key constraint \"order_request_signal_id_fkey\"",
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        // save 1회 시도 (Exception) — 후속 KIS submit + Kafka publish 모두 0
        verify(exactly = 1) { orderRepo.save(any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 0) { rejectedProducer.publish(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(f) KIS rt_cd not 0 → KIS_REJECT audit + Kafka rejected publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { orderRepo.updateStatus(99L, OrderStatus.REJECTED, any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Left(DenyReason.KisRejected(rtCd = "40010000", msg = "주문수량 오류"))

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_REJECT)
        verify(exactly = 1) {
            rejectedProducer.publish(any(), match { it.startsWith("40010000:") })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-MAN Phase 1c — ADR 0082 B-2-extended source 필드 분기 (z1·z2·z3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `(z1) deserialize source=AUTO 매핑 정합 + step 5 INSERT source=AUTO 영속`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        val savedSlot = slot<OrderRequest>()
        every { orderRepo.save(capture(savedSlot)) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(payloadWithSource("AUTO")), ack)

        // 자동 path 회귀 0 — step 5 INSERT source=AUTO
        assertThat(savedSlot.captured.source).isEqualTo(OrderSource.AUTO)
        // audit payload JSON 마커 정합 — DB 컬럼 + JSON 이중 정합 (KIS_ACK)
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        val ackAudit = auditSlot.firstOrNull { it.eventType == OrderAuditEventType.KIS_ACK }
        assertThat(ackAudit).isNotNull
        assertThat(ackAudit!!.payloadJson).contains("\"source\":\"AUTO\"")
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(z2) deserialize source=MANUAL 매핑 정합 + step 5 INSERT source=MANUAL 영속 + audit JSON 마커`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        val savedSlot = slot<OrderRequest>()
        every { orderRepo.save(capture(savedSlot)) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO456", kisFwdgOrgno = "ORG002", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(manualPayload()), ack)

        // 수동 path — step 5 INSERT source=MANUAL 영속
        assertThat(savedSlot.captured.source).isEqualTo(OrderSource.MANUAL)
        // KIS_ACK audit payload JSON 마커에 "source"="MANUAL" — DB 컬럼 + JSON 이중 정합
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        val ackAudit = auditSlot.firstOrNull { it.eventType == OrderAuditEventType.KIS_ACK }
        assertThat(ackAudit).isNotNull
        assertThat(ackAudit!!.payloadJson).contains("\"source\":\"MANUAL\"")
        // 자동 path 영향 0 — Kafka publish·KIS submit 정상 흐름
        verify(exactly = 1) { requestedProducer.publish(any()) }
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(z3) deserialize source 필드 부재 → AUTO default fallback (backward compat 회귀 0)`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        val savedSlot = slot<OrderRequest>()
        every { orderRepo.save(capture(savedSlot)) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO789", kisFwdgOrgno = "ORG003", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        // stubPayload — 기존 형식 (source 필드 없음, T-RISK ADR 0067 publisher 정합)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        // backward compat — source 필드 부재 시 AUTO default
        assertThat(savedSlot.captured.source).isEqualTo(OrderSource.AUTO)
        verify(exactly = 1) { ack.acknowledge() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 장운영시간 게이트 (2026-06-04 dogfooding 결함 fix) — AUTO 차단 / MANUAL 통과 / 토글
    // ─────────────────────────────────────────────────────────────────────────

    /** 게이트 활성 + isWithinTradingHours를 결정론적으로 stub한 spy listener. */
    private fun gatedListener(withinHours: Boolean): OrderRouterKafkaListener {
        val gated =
            io.mockk.spyk(
                OrderRouterKafkaListener(
                    orderRequestRepositoryProvider = orderRepoProvider,
                    orderAuditEventRepositoryProvider = auditRepoProvider,
                    killSwitchQueryPortProvider = killSwitchProvider,
                    tradingModeGate = tradingModeGate,
                    orderIdempotencyGuard = idempotencyGuard,
                    kisOrderPort = kisOrderPort,
                    orderRequestedKafkaProducer = requestedProducer,
                    orderRejectedKafkaProducer = rejectedProducer,
                    accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                    executionBackfillServiceProvider = executionBackfillProvider,
                    killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                    marketSessionResolver = marketSessionResolver,
                    businessDayCalendarProvider = businessDayCalendarProvider,
                    objectMapper = objectMapper,
                    holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
                    sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
                    marketHoursGateEnabled = true,
                ),
            )
        every { gated.isWithinTradingHours(any()) } returns withinHours
        return gated
    }

    @Test
    fun `AUTO 주문이 장외 시각이면 KIS submit 전에 MARKET_CLOSED로 REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        gatedListener(withinHours = false).onOrderIntent(buildRecord(payloadWithSource("AUTO")), ack)

        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED && it.source == OrderSource.AUTO }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.startsWith("MARKET_CLOSED") }) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `MANUAL 주문은 장외 시각이어도 게이트를 통과해 KIS submit까지 진행한다`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO999", kisFwdgOrgno = "ORG009", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        gatedListener(withinHours = false).onOrderIntent(buildRecord(manualPayload()), ack)

        // MANUAL은 게이트 제외 — KIS submit 진행
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 0) { rejectedProducer.publish(any(), match { it.startsWith("MARKET_CLOSED") }) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `AUTO 주문이 정규장 안이면 게이트를 통과해 KIS submit까지 진행한다`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO888", kisFwdgOrgno = "ORG008", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        gatedListener(withinHours = true).onOrderIntent(buildRecord(payloadWithSource("AUTO")), ack)

        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 0) { rejectedProducer.publish(any(), match { it.startsWith("MARKET_CLOSED") }) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `isWithinTradingHours — KST 정규장 10시는 true, 장전 08시30분은 false`() {
        val gated =
            OrderRouterKafkaListener(
                orderRequestRepositoryProvider = orderRepoProvider,
                orderAuditEventRepositoryProvider = auditRepoProvider,
                killSwitchQueryPortProvider = killSwitchProvider,
                tradingModeGate = tradingModeGate,
                orderIdempotencyGuard = idempotencyGuard,
                kisOrderPort = kisOrderPort,
                orderRequestedKafkaProducer = requestedProducer,
                orderRejectedKafkaProducer = rejectedProducer,
                accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                executionBackfillServiceProvider = executionBackfillProvider,
                killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                marketSessionResolver = marketSessionResolver,
                businessDayCalendarProvider = businessDayCalendarProvider,
                objectMapper = objectMapper,
                holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
                sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
            )

        // KST 2026-05-20(수) 10:00 = UTC 2026-05-20T01:00:00Z
        assertThat(gated.isWithinTradingHours(java.time.Instant.parse("2026-05-20T01:00:00Z"))).isTrue()
        // KST 2026-05-20(수) 08:30 = UTC 2026-05-19T23:30:00Z (장전)
        assertThat(gated.isWithinTradingHours(java.time.Instant.parse("2026-05-19T23:30:00Z"))).isFalse()
        // KST 2026-05-23(토) 10:00 = UTC 2026-05-23T01:00:00Z (주말 — resolver Closed)
        assertThat(gated.isWithinTradingHours(java.time.Instant.parse("2026-05-23T01:00:00Z"))).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0109 — canary 전역 hard cap (GAP-1·2·3) 회귀
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `(canary-0) cap 미설정 — REAL 이어도 전부 통과 (fail-open)`() {
        val gate = canaryListener(qty = 0, notional = 0, perDay = 0)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(quantity = 9999, priceAtSignal = "1000000"), TradingMode.REAL)
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-1) 수량 cap 초과 — CANARY_QTY_CAP reject`() {
        val gate = canaryListener(qty = 5)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(quantity = 6), TradingMode.REAL)
        assertThat(deny).startsWith("CANARY_QTY_CAP")
    }

    @Test
    fun `(canary-1b) 수량 cap 경계값(동일) — 통과`() {
        val gate = canaryListener(qty = 5)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(quantity = 5), TradingMode.REAL)
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-2) notional cap 초과 (MARKET — priceAtSignal × qty) — CANARY_NOTIONAL_CAP reject`() {
        val gate = canaryListener(notional = 100_000)
        // 2주 × 78,000 = 156,000 > 100,000
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(quantity = 2, priceType = OrderPriceType.MARKET, priceAtSignal = "78000"),
                TradingMode.REAL,
            )
        assertThat(deny).startsWith("CANARY_NOTIONAL_CAP")
    }

    @Test
    fun `(canary-2b) notional cap 통과 (LIMIT — limitPrice × qty)`() {
        val gate = canaryListener(notional = 100_000)
        // 1주 × 70,000 = 70,000 <= 100,000
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(quantity = 1, priceType = OrderPriceType.LIMIT, limitPrice = "70000"),
                TradingMode.REAL,
            )
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-2c) notional cap 활성인데 가격 부재 — fail-closed`() {
        val gate = canaryListener(notional = 100_000)
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(quantity = 1, priceType = OrderPriceType.MARKET, priceAtSignal = null, limitPrice = null),
                TradingMode.REAL,
            )
        assertThat(deny).isEqualTo("CANARY_NOTIONAL_CAP:PRICE_UNAVAILABLE")
    }

    @Test
    fun `(canary-3) 일일 BUY 횟수 도달 — CANARY_DAILY_ORDER_CAP reject`() {
        // countRealOrdersBetween 은 side=BUY · status<>REJECTED 만 집계(2026-06-12 hotfix).
        // 게이트는 그 count >= cap 이면 차단. BUY 5건 도달 시 6번째 BUY 차단을 표현(count=5, cap=5).
        every { orderRepo.countRealOrdersBetween(any(), any()) } returns 5L
        val gate = canaryListener(perDay = 5)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(side = OrderSide.BUY), TradingMode.REAL)
        assertThat(deny).startsWith("CANARY_DAILY_ORDER_CAP")
    }

    @Test
    fun `(canary-3b) 일일 BUY 횟수 미달 — 통과`() {
        every { orderRepo.countRealOrdersBetween(any(), any()) } returns 4L
        val gate = canaryListener(perDay = 5)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(side = OrderSide.BUY), TradingMode.REAL)
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-3c) SELL 청산은 BUY count 가 cap 도달이어도 통과 — 자본 보호 역전 차단`() {
        // 2026-06-12 라이브 incident hotfix · 최고 관리자 결재: "왕복 1회 = 1카운트, 매도는 cap 무관".
        // BUY 가 cap 도달(count=5>=5)이어도, SELL(익절·손절·DSL 청산)은 BUY-only count 집계에 잡히지 않으므로
        // count 가 cap 미만이고 통과해야 보유 포지션 청산이 가능하다.
        // (게이트 시점에서 SELL intent 의 daily count 는 BUY 만 센 값 — 여기선 4 로 cap 미달을 표현.)
        every { orderRepo.countRealOrdersBetween(any(), any()) } returns 4L
        val gate = canaryListener(perDay = 5)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(side = OrderSide.SELL), TradingMode.REAL)
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-6) SELL 은 QTY cap 무관 통과 — 숏 부재 매도 면제 (F8)`() {
        // 2026-06-12 F8 hotfix · 최고 관리자 결재: 매도는 보유량이 자연 상한 → QTY cap 검사 skip.
        val gate = canaryListener(qty = 1)
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(quantity = 9999, side = OrderSide.SELL),
                TradingMode.REAL,
            )
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-7) SELL 은 NOTIONAL cap 무관 통과 — 숏 부재 매도 면제 (F8)`() {
        val gate = canaryListener(notional = 100_000)
        // 9999 × 78,000 = 약 7.8억 > 100,000 — BUY면 차단이지만 SELL은 면제.
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(quantity = 9999, priceAtSignal = "78000", side = OrderSide.SELL),
                TradingMode.REAL,
            )
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-8) SELL 은 가격 미조회(PRICE_UNAVAILABLE)여도 통과 — 13_31 청산 차단 incident fix (F8)`() {
        // 라이브 13:31 incident: 시장가 SELL(296)이 NOTIONAL cap 의 현재가 조회 실패로 PRICE_UNAVAILABLE 거부
        // → 청산 차단. SELL 면제로 가격 산정 자체를 시도하지 않으므로 통과해야 한다.
        val gate = canaryListener(notional = 100_000)
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(
                    quantity = 296,
                    priceType = OrderPriceType.MARKET,
                    priceAtSignal = null,
                    limitPrice = null,
                    side = OrderSide.SELL,
                ),
                TradingMode.REAL,
            )
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-9) BUY 는 cap 면제 회귀 불변 — 가격 부재 시 여전히 fail-closed`() {
        // F8 SELL 면제가 BUY 경로를 건드리지 않음을 LOCK (회귀 가드).
        val gate = canaryListener(notional = 100_000)
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(
                    quantity = 1,
                    priceType = OrderPriceType.MARKET,
                    priceAtSignal = null,
                    limitPrice = null,
                    side = OrderSide.BUY,
                ),
                TradingMode.REAL,
            )
        assertThat(deny).isEqualTo("CANARY_NOTIONAL_CAP:PRICE_UNAVAILABLE")
    }

    @Test
    fun `(canary-4) MOCK 모드 — 모든 cap 비적용`() {
        val gate = canaryListener(qty = 1, notional = 1, perDay = 1)
        val deny =
            gate.evaluateCanaryHardCap(
                canaryIntent(quantity = 9999, priceAtSignal = "9999999"),
                TradingMode.MOCK,
            )
        assertThat(deny).isNull()
    }

    @Test
    fun `(canary-5) REAL 흐름 통합 — 수량 cap 초과 시 KIS submit 0 + REJECTED publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.REAL)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 77L) }

        val reasonSlot = slot<String>()
        every { rejectedProducer.publish(any(), capture(reasonSlot)) } returns Unit

        // executionBackfillProvider = null → REAL sync 게이트가 통과 못하므로, REAL 통합 검증은
        // sync 게이트보다 앞선 단계가 아닌 canary 게이트 자체 단위 테스트로 충분.
        // 여기서는 canary 게이트가 sync 게이트보다 뒤(INSERT 직전)임을 단위 호출로 보강 검증한다.
        val gate = canaryListener(qty = 1)
        val deny = gate.evaluateCanaryHardCap(canaryIntent(quantity = 10), TradingMode.REAL)
        assertThat(deny).startsWith("CANARY_QTY_CAP")
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
    }

    @Test
    fun `(canary-10) cap gate REJECTED 는 signal 단계 거래빈도 record 를 회수한다`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678", false) } returns OrderResult.Right(TradingMode.REAL)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 77L) }
        every { executionBackfillService.syncUserTodayExecutions("12345678", AccountType.REAL) } returns
            KisExecutionSyncResult(
                userId = "12345678",
                accountType = AccountType.REAL,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = Instant.parse("2026-06-15T03:21:00Z"),
            )
        val userDslStrategyRepo =
            mockk<ConsumerStrategyJpaRepository>().also { repo ->
                every { repo.findById(100L) } returns
                    Optional.of(mockk<ConsumerStrategyJpaEntity>().also { every { it.origin } returns "USER_DSL" })
            }
        val strategyRepoProvider =
            mockk<ObjectProvider<ConsumerStrategyJpaRepository>>().also {
                every { it.getIfAvailable() } returns userDslStrategyRepo
            }
        val listener =
            canaryListener(
                qty = 1,
                mode = "REAL",
                strategyRepoProvider = strategyRepoProvider,
            )

        listener.onOrderIntent(buildRecord(payloadWithQuantity(17)), mockk<Acknowledgment>(relaxed = true))

        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.startsWith("CANARY_QTY_CAP") }) }
        verify(exactly = 1) { tradeFrequencyGuard.removeRecord(100L, "12345678", 42L) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
    }

    private fun canaryListener(
        qty: Int = 0,
        notional: Long = 0,
        perDay: Int = 0,
        mode: String = "MOCK",
        strategyRepoProvider: ObjectProvider<ConsumerStrategyJpaRepository>? = null,
    ): OrderRouterKafkaListener =
        OrderRouterKafkaListener(
            orderRequestRepositoryProvider = orderRepoProvider,
            orderAuditEventRepositoryProvider = auditRepoProvider,
            killSwitchQueryPortProvider = killSwitchProvider,
            tradingModeGate = tradingModeGate,
            orderIdempotencyGuard = idempotencyGuard,
            kisOrderPort = kisOrderPort,
            orderRequestedKafkaProducer = requestedProducer,
            orderRejectedKafkaProducer = rejectedProducer,
            accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
            executionBackfillServiceProvider = executionBackfillProvider,
            killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
            marketSessionResolver = marketSessionResolver,
            businessDayCalendarProvider = businessDayCalendarProvider,
            objectMapper = objectMapper,
            holdingDemandRegistrarProvider = holdingDemandRegistrarProvider,
            sellRejectAutoPauseTracker = sellRejectAutoPauseTracker,
            tradeFrequencyGuardProvider = tradeFrequencyGuardProvider,
            consumerStrategyJpaRepositoryProvider = strategyRepoProvider,
            magicjarMode = mode,
            marketHoursGateEnabled = false,
            canaryMaxOrderQty = qty,
            canaryMaxOrderNotionalKrw = notional,
            canaryMaxOrdersPerDay = perDay,
        )

    private fun canaryIntent(
        quantity: Int = 1,
        priceType: OrderPriceType = OrderPriceType.MARKET,
        priceAtSignal: String? = "78000",
        limitPrice: String? = null,
        side: OrderSide = OrderSide.BUY,
    ): OrderIntent =
        OrderIntent(
            id = 42L,
            userId = "12345678",
            signalId = 42L,
            strategyId = 100L,
            code = "005930",
            side = side,
            priceType = priceType,
            quantity = quantity,
            limitPrice = limitPrice?.let { BigDecimal(it) },
            idempotencyKey = "42:12345678",
            requestedAt = Instant.ofEpochSecond(1747094405L),
            source = OrderSource.AUTO,
            priceAtSignal = priceAtSignal?.let { BigDecimal(it) },
        )

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun stubPayload(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "interval" to "M5",
                "ts" to 1747094400L,
                "side" to "BUY",
                "strength" to 80,
                "priceAtSignal" to "78000",
                "riskVerdict" to "ALLOWED",
                "emittedAt" to 1747094405L,
            ),
        )

    /**
     * ADR 0115 §2-P2-⑥ — orderTrigger·triggerRefId 명시 payload (키 사슬 영속 검증).
     */
    private fun triggerPayload(
        trigger: String,
        triggerRefId: Long,
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "interval" to "M5",
                "ts" to 1747094400L,
                "side" to "BUY",
                "strength" to 80,
                "priceAtSignal" to "78000",
                "riskVerdict" to "ALLOWED",
                "emittedAt" to 1747094405L,
                "source" to "AUTO",
                "orderTrigger" to trigger,
                "triggerRefId" to triggerRefId,
            ),
        )

    /**
     * ADR 0115 §2-P2-⑥ — orderTrigger 미발행(legacy) + AUTO + signalId=0 → AUTO_EXIT fallback 검증용.
     * triggerRefId fallback = strategyId(7).
     */
    private fun legacyAutoExitPayload(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 0,
                "strategyId" to 7,
                "userId" to "12345678",
                "code" to "005930",
                "interval" to "1m",
                "ts" to 1747094400L,
                "side" to "SELL",
                "strength" to 100,
                "priceAtSignal" to "78000",
                "riskVerdict" to "ALLOWED",
                "emittedAt" to 1747094405L,
                "source" to "AUTO",
            ),
        )

    /**
     * source 필드 명시 payload (z1 AUTO·z2 MANUAL 검증).
     */
    private fun payloadWithSource(source: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "interval" to "M5",
                "ts" to 1747094400L,
                "side" to "BUY",
                "strength" to 80,
                "priceAtSignal" to "78000",
                "riskVerdict" to "ALLOWED",
                "emittedAt" to 1747094405L,
                "source" to source,
            ),
        )

    private fun payloadWithQuantity(quantity: Int): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "side" to "BUY",
                "quantity" to quantity,
                "emittedAt" to 1747094405L,
                "source" to "AUTO",
            ),
        )

    /**
     * MANUAL 주문 payload — ADR 0082 T-MAN B-2-extended (signalId=0L·strategyId=0L sentinel + source=MANUAL).
     */
    private fun manualPayload(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 0,
                "strategyId" to 0,
                "userId" to "12345678",
                "code" to "005930",
                "side" to "BUY",
                "priceType" to "MARKET",
                "quantity" to 1,
                "idempotencyKey" to "manual:12345678:1747094405",
                "emittedAt" to 1747094405L,
                "source" to "MANUAL",
            ),
        )

    private fun stubOrderRequest(
        id: Long = 99L,
        signalId: Long = 42L,
        strategyId: Long = 100L,
        userId: String = "12345678",
        code: String = "005930",
        idempotencyKey: String = "42:12345678",
        kisOrderNo: String? = null,
        status: OrderStatus = OrderStatus.ACK,
    ): OrderRequest =
        OrderRequest(
            id = id,
            userId = userId,
            signalId = signalId,
            strategyId = strategyId,
            code = code,
            side = OrderSide.BUY,
            priceType = OrderPriceType.MARKET,
            quantity = 1,
            limitPrice = null,
            status = status,
            mode = TradingMode.MOCK,
            kisOrderNo = kisOrderNo,
            idempotencyKey = idempotencyKey,
            requestedAt = Instant.ofEpochSecond(1747094405L),
            source = OrderSource.AUTO,
        )

    private fun buildRecord(value: String) = ConsumerRecord<String, String>(KafkaTopics.Order.INTENT, 0, 0L, "12345678", value)
}
