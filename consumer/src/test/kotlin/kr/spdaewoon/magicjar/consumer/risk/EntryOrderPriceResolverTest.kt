package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.EntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * [EntryOrderPriceResolver] 배선 단위 테스트 — ADR 0114 §3-1 (R2-C2a) · opt-in 게이트 hotfix(2026-06-08).
 *
 * 순수 계산은 EntryPricePolicyTest(strategy-engine)가 보장 — 본 테스트는 배선(opt-in 게이트·호가 주입·전역 default·결과 매핑)만 검증한다.
 *
 * ### opt-in 게이트 매트릭스 (자본 경로 회귀 hotfix)
 * marketable-limit은 (전략 entry_policy 명시 OR 전역 limit-enabled=true) 일 때만 적용. 그 외 → MARKET(호가 조회 0).
 * - entry_policy=null + limit-enabled=false(default) → Market (호가 조회 0 · Skip 0) ← 회귀 정정 핵심 케이스
 * - entry_policy=null + limit-enabled=true → marketable-limit
 * - entry_policy 명시 + limit-enabled=false → marketable-limit (전략 명시는 toggle 무관)
 */
class EntryOrderPriceResolverTest {
    private val entryQuotePort = mockk<EntryQuotePort>(relaxed = true)
    private val now = Instant.parse("2026-05-22T01:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun resolver(
        quotePortPresent: Boolean = true,
        limitEnabled: Boolean = false,
    ): EntryOrderPriceResolver =
        EntryOrderPriceResolver(
            entryQuotePortProvider = if (quotePortPresent) providerOf(entryQuotePort) else emptyProvider(),
            limitEnabled = limitEnabled,
            clock = clock,
        )

    private fun buySignal(price: String = "50000"): Signal =
        Signal(
            id = 7L,
            strategyId = 100L,
            userId = "user-A",
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = now,
            side = SignalSide.BUY,
            strength = 80,
            reason = "RULE",
            priceAtSignal = BigDecimal(price),
        )

    private fun strategyWith(entryPolicy: EntryPolicy?): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "t", buyCondition = null),
                    entryPolicy = entryPolicy,
                ),
            complexity = StrategyComplexity(1, 1, 0, 1, ComplexityClass.LOW),
        )

    // === opt-in 게이트 회귀 정정 케이스 (hotfix 2026-06-08) ===

    @Test
    fun `Market — entry_policy null + limit-enabled false(default)면 MARKET (호가 조회 0)`() {
        // 회귀 핵심: 과거엔 globalDefault marketable-limit 무조건 적용 → 호가 미가용 종목 전부 NO_QUOTE 스킵 → BUY 0.
        // 정정: entry_policy 없는 전략은 MARKET 결정 + 호가 조회(latestQuote) 자체를 하지 않는다(REST TPS 0).
        val decision = resolver(limitEnabled = false).resolve(buySignal(), strategyWith(null))

        assertThat(decision).isEqualTo(EntryOrderDecision.Market)
        verify(exactly = 0) { entryQuotePort.latestQuote(any()) }
    }

    @Test
    fun `Market — strategy null(미발견)이어도 limit-enabled false면 MARKET (호가 조회 0)`() {
        val decision = resolver(limitEnabled = false).resolve(buySignal(), null)

        assertThat(decision).isEqualTo(EntryOrderDecision.Market)
        verify(exactly = 0) { entryQuotePort.latestQuote(any()) }
    }

    @Test
    fun `Market — limit-enabled false면 EntryQuotePort 부재여도 호가 조회 0 + MARKET (스킵 아님)`() {
        // 과거 NoQuote skip → 정정: 미적용이므로 호가 경로 자체 미진입 → MARKET.
        val decision = resolver(quotePortPresent = false, limitEnabled = false).resolve(buySignal(), strategyWith(null))

        assertThat(decision).isEqualTo(EntryOrderDecision.Market)
    }

    @Test
    fun `marketable-limit — entry_policy null이라도 limit-enabled true면 전역 적용(Enter)`() {
        // 전역 opt-in 스위치: entry_policy 없는 전략도 marketable-limit. ask1 50000 ≤ cap 50100 → Enter.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49950"), askQty1 = 100L, asOf = now)

        val decision = resolver(limitEnabled = true).resolve(buySignal(), strategyWith(null))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Enter::class.java)
        assertThat((decision as EntryOrderDecision.Enter).limitPrice).isEqualByComparingTo("50000")
    }

    @Test
    fun `marketable-limit — limit-enabled true + 호가 미가용이면 NoQuote skip(시장가 fallback 0)`() {
        // limit-enabled opt-in 했으면 호가 미가용 = Skip(슬리피지 캡 자기 부정 회피 — §3-1-5 LOCK).
        every { entryQuotePort.latestQuote("005930") } returns null

        val decision = resolver(limitEnabled = true).resolve(buySignal(), strategyWith(null))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_NO_QUOTE")
    }

    // === 전략 entry_policy 명시 = marketable-limit 적용 (toggle 무관) ===

    @Test
    fun `Enter — entry_policy 명시 전략은 limit-enabled false여도 marketable-limit (ask1 상한 이내 LIMIT)`() {
        // priceAtSignal 50000, override slippage 0.2% → cap 50100. ask1 50000 ≤ cap → Enter.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49950"), askQty1 = 100L, asOf = now)

        val explicitPolicy = EntryPolicy(maxEntrySlippagePct = BigDecimal("0.2"))
        val decision = resolver(limitEnabled = false).resolve(buySignal(), strategyWith(explicitPolicy))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Enter::class.java)
        val enter = decision as EntryOrderDecision.Enter
        assertThat(enter.limitPrice).isEqualByComparingTo("50000")
        assertThat(enter.entryTimeoutSec).isEqualTo(15) // entry_policy의 timeout null → 전역 default
    }

    @Test
    fun `Skip PriceCapExceeded — entry_policy 명시 전략, ask1 상한 초과면 스킵(시장가 0)`() {
        // ask1 50200 > cap 50100 → PriceCapExceeded.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50200"), bid1 = BigDecimal("50150"), askQty1 = 100L, asOf = now)

        val decision =
            resolver(limitEnabled = false).resolve(buySignal(), strategyWith(EntryPolicy(maxEntrySlippagePct = BigDecimal("0.2"))))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Skip::class.java)
        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_ENTRY_PRICE_CAP")
    }

    @Test
    fun `Skip SpreadTooWide — entry_policy 명시 전략, 스프레드 초과면 스킵`() {
        // ask1 50000, bid1 49000 → spread (1000)/49500*100 ≈ 2.02% > 0.3%.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49000"), askQty1 = 100L, asOf = now)

        val decision =
            resolver(limitEnabled = false).resolve(buySignal(), strategyWith(EntryPolicy(maxSpreadPct = BigDecimal("0.3"))))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_SPREAD")
    }

    @Test
    fun `Skip NoQuote — entry_policy 명시 전략, 호가 미가용(null)이면 스킵`() {
        every { entryQuotePort.latestQuote("005930") } returns null

        val decision =
            resolver(limitEnabled = false).resolve(buySignal(), strategyWith(EntryPolicy(maxEntrySlippagePct = BigDecimal("0.2"))))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_NO_QUOTE")
    }

    @Test
    fun `Skip QuoteStale — entry_policy 명시 전략, quoteAsOf 임계 초과 경과면 스킵(NO_QUOTE 코드)`() {
        // asOf가 now보다 10초 과거 → default 3초 임계 초과 → QuoteStale(auditCode=SIGNAL_SKIPPED_NO_QUOTE).
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(
                ask1 = BigDecimal("50000"),
                bid1 = BigDecimal("49950"),
                askQty1 = 100L,
                asOf = Instant.parse("2026-05-22T00:59:50Z"),
            )

        val decision =
            resolver(limitEnabled = false).resolve(buySignal(), strategyWith(EntryPolicy(maxEntrySlippagePct = BigDecimal("0.2"))))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_NO_QUOTE")
    }

    @Test
    fun `entry_policy override — 더 넓은 슬리피지 상한이 적용된다 (limit-enabled 무관)`() {
        // override slippage 1.0% → cap 50500. ask1 50300 > 전역 cap(50100)이지만 override cap 이내 → Enter.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50300"), bid1 = BigDecimal("50250"), askQty1 = 100L, asOf = now)

        val override = EntryPolicy(maxEntrySlippagePct = BigDecimal("1.0"), entryTimeoutSec = 30)
        val decision = resolver(limitEnabled = false).resolve(buySignal(), strategyWith(override))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Enter::class.java)
        val enter = decision as EntryOrderDecision.Enter
        assertThat(enter.limitPrice).isEqualByComparingTo("50300")
        assertThat(enter.entryTimeoutSec).isEqualTo(30) // 전략 override
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    private fun <T : Any> emptyProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns null }
}
