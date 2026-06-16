package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService
import kr.spdaewoon.magicjar.consumer.order.KisExecutionSyncResult
import kr.spdaewoon.magicjar.consumer.order.OrderCancelledKafkaProducer
import kr.spdaewoon.magicjar.consumer.order.RestingSellOrderCanceller
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.PaperCapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskNotifier
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.SendResult
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

/**
 * ADR 0122 R3 — 발굴→MOCK 페이퍼 dispatch **데이터 흐름 시나리오 테스트 일괄** (spec §4 R3 strategy-engine 몫).
 *
 * development-rules §4 "코드 먼저, 테스트 마지막 일괄"의 그 일괄 — R1(StrategyOrigin·PaperCapitalSnapshotProvider·
 * CapitalSnapshotSelector) + R2(DaytradePaperService·PaperCapitalSnapshotProviderAdapter·listener capital 분기)가
 * main에 머지된 뒤, 페이퍼 자본 경로의 **e2e 데이터 흐름**을 무인프라(JVM 단위·mock 합성 0)로 검증한다.
 * fake/in-memory port 패턴은 consumer `LedgerInvariantScenarioTest` 선례 정합.
 *
 * ### 시나리오 (spec §4 R3 LOCK)
 * - **S-2 시그널→주문 capital 분기**: 페이퍼 전략(origin=PAPER_DISCOVERY) 시그널 → buildContext가 가상 자본
 *   provider + `<userId>:paper` RiskConfig 키로 RiskGateway 평가 → 가상 자본 예산 기반 주문. 실
 *   CapitalSnapshotProvider(KIS 잔고) **미호출**(오염 0 — spec §1-3).
 * - **S-3 fail-fast**: 페이퍼 전략인데 PaperCapitalSnapshotProvider 미배선 → CapitalSnapshotSelector가
 *   `PaperCapitalProviderUnavailableException` → 실 잔고 silent 폴백 금지(헌법 §11). **F10(2026-06-12)**:
 *   과거 capital 0 fabricate → POSITION_LIMIT BLOCKED 였으나, 조회 불가를 0 으로 위장 금지 → BUY 시그널
 *   skip(SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE audit, 위반·주문 0). 실 provider 미호출 검증.
 * - **S-4 격리(zero-regression)**: 같은 user의 USER_DSL 전략은 항상 실 capital 경로 + `<userId>` 실 키.
 *   페이퍼 provider **미호출** — 비페이퍼 동작 byte 동일.
 * - **S-6 키 사슬**: 페이퍼 시그널 파생 주문 intent는 `orderTrigger=STRATEGY_SIGNAL` + `triggerRefId=signalId`
 *   (ADR 0115 P2-⑥). 페이퍼 식별 1급 키 = STRATEGY_SIGNAL + mode=MOCK + strategy.origin=PAPER_DISCOVERY
 *   조합(spec §3-R1 (a) — enum 발명 0).
 *
 * S-1(promote → Strategy+RiskConfig 파생 행)·S-5(soft delete 제외)는 api 모듈 `DaytradePaperServiceTest`가
 * 커버(R2 산출) — 본 테스트는 consumer 자본 경로 e2e 공백을 채운다. 회귀(기존 USER_DSL 경로 GREEN)는 S-4 +
 * 기존 `SignalGeneratedKafkaListenerTest` suite로 보장.
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1 + development-rules §5)
 */
class DaytradePaperPathScenarioTest {
    private val objectMapper = ObjectMapper()
    private val riskConfigRepository = mockk<RiskConfigRepository>()
    private val riskEventRepository = mockk<RiskEventRepository>(relaxed = true)
    private val riskPositionPort = mockk<RiskPositionPort>()
    private val strategyPositionPort = mockk<kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort>(relaxed = true)
    private val signalRiskStatusUpdater = mockk<SignalRiskStatusUpdater>(relaxed = true)
    private val riskNotifier = mockk<RiskNotifier>(relaxed = true)
    private val killSwitchQueryPort = mockk<KillSwitchQueryPort>()

    // 실 KIS 잔고 provider — 페이퍼 경로에서는 절대 호출되면 안 된다(spec §1-3 격리 LOCK 검증 대상).
    private val realCapitalProvider = mockk<CapitalSnapshotProvider>()

    // 페이퍼 가상 자본 provider — origin=PAPER_DISCOVERY 전략만 사용.
    private val paperCapitalProvider = mockk<PaperCapitalSnapshotProvider>()

    private val tradeFrequencyGuard = mockk<TradeFrequencyRedisGuard>(relaxed = true)
    private val dailyPnlCalculator = mockk<DailyPnlCalculator>()
    private val orderIntentKafkaProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val riskViolationKafkaProducer = mockk<RiskViolationKafkaProducer>(relaxed = true)
    private val autoPauseGuard = mockk<AutoPauseGuard>(relaxed = true)
    private val executionBackfillService = mockk<KisExecutionBackfillService>(relaxed = true)
    private val orderRequestRepository = mockk<OrderRequestRepository>()
    private val strategyLookupPort = mockk<StrategyLookupPort>()
    private val kisBalanceQueryPort = mockk<KisBalanceQueryPort>()

    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val orderCancelledKafkaProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val restingSellOrderCanceller = RestingSellOrderCanceller(kisOrderPort, orderCancelledKafkaProducer)
    private val entryQuotePort = mockk<EntryQuotePort>()
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    private val now = Instant.parse("2026-06-10T01:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    // ── S-2 시그널→주문 capital 분기 ──────────────────────────────────────────

    @Test
    fun `S-2 페이퍼 전략 시그널은 가상 자본 + paper RiskConfig 키로 평가하고 실 KIS 잔고를 호출하지 않는다`() {
        // 페이퍼 전략(origin=PAPER_DISCOVERY) — buildContext가 isPaper=true → riskConfigKey="user-A:paper" + 가상 자본.
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(paperStrategy())
        // paper 키로만 config 조회 — 실 키("user-A") 조회는 일어나선 안 된다.
        every { riskConfigRepository.getOrDefault("user-A:paper", now) } returns paperConfig()
        every { paperCapitalProvider.getCapital("user-A") } returns BigDecimal("10000000") // 가상 자본 1천만(ADR 0122 §8-2)
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // 가상 자본 1천만 + paper 프로필 → 총한도 min(절대 1M, 순자산 10M×10%=1M)=1M, 노출 0 → 매수가능 min(1M, 예수금 10M)=1M → 1M/50000 = 20주.
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.MARKET, null) }
        // spec §1-3 격리 LOCK — 페이퍼 평가는 실 KIS 잔고 provider를 절대 호출하지 않는다(오염 0).
        verify(exactly = 0) { realCapitalProvider.getCapital(any()) }
        // 실 사용자 키("user-A")로 RiskConfig 조회도 일어나지 않는다(파생 키만).
        verify(exactly = 0) { riskConfigRepository.getOrDefault("user-A", any()) }
    }

    @Test
    fun `S-2 페이퍼 가상 자본이 작으면 예산도 줄어 더 적은 수량을 발행한다(가상 자본이 실제로 사이징을 가른다)`() {
        // 계좌 전체 공유 풀 재설계(사용자 결재 2026-06-16) — 매수가능 = min(총한도−전체노출, 예수금).
        // 페이퍼는 예수금 개념이 없어 가상 자본을 그대로 순자산·예수금으로 본다.
        // 총한도 = min(paper.maxPositionAmount=1M, 순자산(가상 자본 50만)×maxPositionPercent 10% = 5만) = 5만(비율 binding).
        // 전체노출 0 → 남은 5만 → 매수가능 min(5만, 예수금 50만) = 5만 → 5만/50000 = 1주.
        // (S-2 1천만 케이스의 20주와 대비 — 실 KIS 잔고가 아니라 가상 자본이 수량을 가름을 증명.)
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(paperStrategy())
        every { riskConfigRepository.getOrDefault("user-A:paper", now) } returns paperConfig()
        every { paperCapitalProvider.getCapital("user-A") } returns BigDecimal("500000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 1, OrderPriceType.MARKET, null) }
        verify(exactly = 0) { realCapitalProvider.getCapital(any()) }
    }

    // ── S-3 fail-fast — paper provider 미배선 ─────────────────────────────────

    @Test
    fun `S-3 페이퍼 전략인데 paper provider 미배선이면 실 잔고 폴백 0 없이 BUY 시그널 skip(F10 — 0 fabricate 금지)`() {
        // CapitalSnapshotSelector.forStrategy(real, null)가 페이퍼 요청에 PaperCapitalProviderUnavailableException →
        // buildContext capital 분기가 실 폴백 없이 조회 불가로 간주.
        // F10(2026-06-12 결재) 변경: 과거엔 capital 0 fabricate → POSITION_LIMIT BLOCKED 였으나, "조회 불가"를 0 으로
        //   위장하는 것이 14:12 incident 의 근본이었다. 이제 BUY capital 불가는 위반이 아니라 skip(audit) 으로 종결한다.
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(paperStrategy())
        every { riskConfigRepository.getOrDefault("user-A:paper", now) } returns paperConfig()
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        // paperProvider 미배선(null) — 페이퍼 비활성 환경 재현.
        listener(paperProvider = nullableProvider()).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // F10 — capital 조회 불가 → skip audit 만. POSITION_LIMIT 위반 평가·주문 0.
        verify(exactly = 1) { systemEventLogger.emit(match { it.action == "SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE" }) }
        verify(exactly = 0) { signalRiskStatusUpdater.markBlocked(any(), any(), any()) }
        verify(exactly = 0) { riskEventRepository.save(any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
        // 헌법 §11 — 페이퍼 fail-fast가 실 KIS 잔고로 silent 폴백하지 않는다(오염 0).
        verify(exactly = 0) { realCapitalProvider.getCapital(any()) }
        verify(exactly = 0) { realCapitalProvider.capitalOutcome(any()) }
    }

    // ── S-4 격리 — USER_DSL 전략은 항상 실 capital 경로(zero-regression) ────────

    @Test
    fun `S-4 같은 user의 USER_DSL 전략은 실 capital + 실 RiskConfig 키 경로 - paper provider 미호출(byte 동일)`() {
        // origin=USER_DSL(default) — isPaper=false → 실 키("user-A") + 실 capital provider. 페이퍼 경로 자원 0.
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userDslStrategy())
        every { riskConfigRepository.getOrDefault("user-A", now) } returns realConfig()
        every { realCapitalProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        // 실 capital 1천만 + 실 config(1M/10%) → 20주.
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.MARKET, null) }
        // zero-regression — USER_DSL은 실 capital provider만 쓰고 페이퍼 provider는 건드리지 않는다.
        // F10 — 주문경로 capital 산출은 capitalOutcome 경로(0 fabricate 금지).
        // 2026-06-15 hotfix — BUY capital preflight + buildContext fresh-cache 경로로 2회 호출된다.
        verify(exactly = 2) { realCapitalProvider.capitalOutcome("user-A") }
        verify(exactly = 0) { paperCapitalProvider.getCapital(any()) }
        // 파생 키("user-A:paper") config 조회 0 — 실 키만.
        verify(exactly = 0) { riskConfigRepository.getOrDefault("user-A:paper", any()) }
    }

    // ── S-6 키 사슬 — 페이퍼 주문 식별 조합 ──────────────────────────────────

    @Test
    fun `S-6 페이퍼 시그널 파생 주문 intent는 orderTrigger=STRATEGY_SIGNAL + triggerRefId=signalId로 식별 가능하다`() {
        // 실 OrderIntentKafkaProducer로 payload 발행을 가로채 키 사슬(P2-⑥)을 검증한다.
        // 페이퍼 식별 1급 키 = STRATEGY_SIGNAL + mode=MOCK + strategy.origin=PAPER_DISCOVERY (spec §3-R1 (a) — enum 발명 0).
        val kafkaTemplate = mockk<org.springframework.kafka.core.KafkaTemplate<String, String>>()
        val payloadSlot = slot<String>()
        every { kafkaTemplate.send(KafkaTopics.Order.INTENT, "user-A", capture(payloadSlot)) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
        val realProducer =
            OrderIntentKafkaProducer(
                kafkaTemplate = kafkaTemplate,
                objectMapper = objectMapper,
                outboxRepositoryProvider = nullableProvider(),
                persistenceMetricsProvider = nullableProvider(),
            )

        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(paperStrategy())
        every { riskConfigRepository.getOrDefault("user-A:paper", now) } returns paperConfig()
        every { paperCapitalProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(orderIntentProducer = realProducer).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // 발행 payload의 키 사슬 — orderTrigger STRATEGY_SIGNAL + triggerRefId = signal id(77).
        assertThat(payloadSlot.isCaptured).isTrue()
        @Suppress("UNCHECKED_CAST")
        val published = objectMapper.readValue(payloadSlot.captured, Map::class.java) as Map<String, Any?>
        assertThat(published["orderTrigger"]).isEqualTo(OrderTrigger.STRATEGY_SIGNAL.name)
        assertThat((published["triggerRefId"] as Number).toLong()).isEqualTo(77L)
        assertThat(published["source"]).isEqualTo("AUTO")
        assertThat((published["signalId"] as Number).toLong()).isEqualTo(77L)
        assertThat((published["strategyId"] as Number).toLong()).isEqualTo(100L)
        // 발사 전략의 origin이 PAPER_DISCOVERY — 위 키 조합 + mode=MOCK(dev 강제)와 함께 페이퍼 주문임을 식별.
        assertThat(paperStrategy().isPaperDiscovery()).isTrue()
    }

    // ── helper (SignalGeneratedKafkaListenerTest 패턴 정합) ───────────────────

    private fun listener(
        magicjarMode: String = "MOCK",
        paperProvider: ObjectProvider<PaperCapitalSnapshotProvider> = providerOf(paperCapitalProvider),
        orderIntentProducer: OrderIntentKafkaProducer = orderIntentKafkaProducer,
    ): SignalGeneratedKafkaListener {
        every { orderRequestRepository.findOpenByUserId(any()) } returns emptyList()
        every { kisBalanceQueryPort.fetchFullBalance(any(), any()) } returns sampleKisBalance()
        every { entryQuotePort.latestQuote(any()) } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49950"), askQty1 = 100L, asOf = now)
        val entryOrderPriceResolver =
            EntryOrderPriceResolver(
                entryQuotePortProvider = providerOf(entryQuotePort),
                limitEnabled = false, // entry_policy 없는 페이퍼 전략 → MARKET 경로(NO_QUOTE 스킵 0 — 자본 경로 회귀 정정).
                clock = clock,
            )
        return SignalGeneratedKafkaListener(
            objectMapper = objectMapper,
            riskGatewayEvaluator = RiskGatewayEvaluator(),
            tradeFrequencyGuard = tradeFrequencyGuard,
            dailyPnlCalculator = dailyPnlCalculator,
            capitalSnapshotProvider = realCapitalProvider,
            paperCapitalSnapshotProviderProvider = paperProvider,
            orderIntentKafkaProducer = orderIntentProducer,
            riskViolationKafkaProducer = riskViolationKafkaProducer,
            autoPauseGuard = autoPauseGuard,
            riskConfigRepositoryProvider = providerOf(riskConfigRepository),
            riskEventRepositoryProvider = providerOf(riskEventRepository),
            riskPositionPortProvider = providerOf(riskPositionPort),
            strategyPositionPortProvider = providerOf(strategyPositionPort),
            signalRiskStatusUpdaterProvider = providerOf(signalRiskStatusUpdater),
            riskNotifierProvider = providerOf(riskNotifier),
            killSwitchQueryPortProvider = providerOf(killSwitchQueryPort),
            orderRequestRepositoryProvider = providerOf(orderRequestRepository),
            strategyLookupPortProvider = providerOf(strategyLookupPort),
            kisBalanceQueryPortProvider = providerOf(kisBalanceQueryPort),
            executionBackfillServiceProvider = passingSyncProvider(magicjarMode),
            entryOrderPriceResolver = entryOrderPriceResolver,
            restingSellOrderCanceller = restingSellOrderCanceller,
            systemEventLoggerProvider = providerOf(systemEventLogger),
            magicjarMode = magicjarMode,
            clock = clock,
        )
    }

    /** 발굴 페이퍼 승격 전략 (origin=PAPER_DISCOVERY · ACTIVE · RiskPlan 보유 → ensureRiskPlanIfBuy 통과). */
    private fun paperStrategy(): Strategy = sampleStrategy().copy(origin = StrategyOrigin.PAPER_DISCOVERY, status = StrategyStatus.ACTIVE)

    /** 사용자 작성 DSL 전략 (origin=USER_DSL default · 실 capital 경로). */
    private fun userDslStrategy(): Strategy = sampleStrategy().copy(origin = StrategyOrigin.USER_DSL)

    private fun sampleStrategy(): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                    fixedStopLoss = BigDecimal("2.00"),
                    takeProfitPct = BigDecimal("3.00"),
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 1,
                    indicatorCallCount = 0,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
        )

    /** 페이퍼 RiskConfig 프로필 (DaytradePaperService.ensurePaperRiskProfile 값 정합 — ADR 0121 사이징). */
    private fun paperConfig(): RiskConfig =
        RiskConfig(
            userId = "user-A:paper",
            maxPositionAmount = BigDecimal("1000000"),
            maxPositionPercent = BigDecimal("10.00"),
            dailyLossLimitAmount = null,
            dailyLossLimitPercent = BigDecimal("3.00"),
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = now,
            updatedAt = now,
        )

    /** 실 사용자 RiskConfig (defaultFor 정합 — S-4 비페이퍼 경로). */
    private fun realConfig(): RiskConfig = paperConfig().copy(userId = "user-A")

    private fun sampleKisBalance(): KisAccountBalance =
        KisAccountBalance(
            userId = "user-A",
            accountType = kr.spdaewoon.magicjar.domain.user.AccountType.MOCK,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("10000000"),
                    cashD1 = BigDecimal("10000000"),
                    cashD2 = BigDecimal("10000000"),
                    totalEvaluation = BigDecimal("10000000"),
                    netAssetValue = BigDecimal("10000000"),
                    totalPurchaseAmount = BigDecimal.ZERO,
                    totalHoldingEvaluation = BigDecimal.ZERO,
                    totalProfitLoss = BigDecimal.ZERO,
                    fetchedAt = java.time.Instant.parse("2026-06-10T01:00:00Z"),
                ),
            holdings = emptyList(),
            fetchedAt = java.time.Instant.parse("2026-06-10T01:00:00Z"),
        )

    private fun payload(
        side: String,
        price: String,
    ): String =
        objectMapper.writeValueAsString(
            buildMap<String, Any?> {
                put("id", 77)
                put("strategyId", 100)
                put("userId", "user-A")
                put("code", "005930")
                put("interval", CandleInterval.FIVE_MIN.code)
                put("ts", now.epochSeconds)
                put("side", side)
                put("strength", 80)
                put("reason", "test")
                put("priceAtSignal", price)
            },
        )

    private fun record(value: String) = ConsumerRecord(KafkaTopics.Risk.SIGNAL_GENERATED, 0, 0L, "user-A", value)

    private fun ack(): Acknowledgment = mockk(relaxed = true)

    private fun passingSyncProvider(magicjarMode: String): ObjectProvider<KisExecutionBackfillService> {
        val accountType =
            kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
                .accountTypeOrMock(magicjarMode)
        every { executionBackfillService.syncUserTodayExecutions("user-A", accountType) } returns
            KisExecutionSyncResult(
                userId = "user-A",
                accountType = accountType,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = java.time.Instant.parse("2026-06-10T01:00:00Z"),
            )
        return providerOf(executionBackfillService)
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }

    private inline fun <reified T : Any> nullableProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns null
        }
}
