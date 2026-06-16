package kr.spdaewoon.magicjar.strategy.entry

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [EntryPricePolicy] 단위 테스트 (순수 함수 — 결정론·저비용 · ADR 0114 §3-1·§6).
 *
 * 판정 순서 LOCK: 호가 가용성 → stale → 스프레드 → 슬리피지 캡 → Enter.
 * **시장가 fallback 0** 불변: 어떤 Skip에서도 MARKET 발행 0(Skip은 audit만 — R2).
 */
class EntryPricePolicyTest {
    private val evalAt = Instant.parse("2026-06-07T01:00:00Z")
    private val freshQuote = Instant.parse("2026-06-07T00:59:59Z") // 1초 전 — fresh

    private fun input(
        priceAtSignal: String = "10000",
        ask1: String? = "10010",
        bid1: String? = "10000",
        askQty1: Long? = 1000,
        maxEntrySlippagePct: String = "0.2",
        maxSpreadPct: String = "0.3",
        quoteAsOf: Instant? = freshQuote,
        evalAt: Instant = this.evalAt,
        maxQuoteAgeSec: Long = EntryPricePolicy.DEFAULT_MAX_QUOTE_AGE_SEC,
        attempt: Int = 0,
    ) = EntryPriceInput(
        priceAtSignal = BigDecimal(priceAtSignal),
        ask1 = ask1?.let { BigDecimal(it) },
        bid1 = bid1?.let { BigDecimal(it) },
        askQty1 = askQty1,
        maxEntrySlippagePct = BigDecimal(maxEntrySlippagePct),
        maxSpreadPct = BigDecimal(maxSpreadPct),
        quoteAsOf = quoteAsOf,
        evalAt = evalAt,
        maxQuoteAgeSec = maxQuoteAgeSec,
        attempt = attempt,
    )

    @Test
    fun `enters at ask1 when within cap and spread ok`() {
        // 기준가 10000, slippage 0.2% → cap = 10020 (호가단위 10원 정렬). ask1 10010 ≤ cap → Enter
        val d = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10010", bid1 = "10000"))
        assertTrue(d is EntryPriceDecision.Enter)
        assertEquals(BigDecimal("10010"), (d as EntryPriceDecision.Enter).limitPrice)
    }

    @Test
    fun `skips NoQuote when ask1 missing — no market fallback`() {
        val d = EntryPricePolicy.evaluate(input(ask1 = null))
        assertEquals(EntryPriceDecision.Skip.NoQuote, d)
        assertEquals("SIGNAL_SKIPPED_NO_QUOTE", (d as EntryPriceDecision.Skip).auditCode)
    }

    @Test
    fun `skips NoQuote when bid1 non-positive`() {
        assertEquals(EntryPriceDecision.Skip.NoQuote, EntryPricePolicy.evaluate(input(bid1 = "0")))
    }

    @Test
    fun `skips QuoteStale when quoteAsOf null`() {
        assertEquals(EntryPriceDecision.Skip.QuoteStale, EntryPricePolicy.evaluate(input(quoteAsOf = null)))
    }

    @Test
    fun `skips QuoteStale when quote older than threshold`() {
        // evalAt 01:00:00, quoteAsOf 00:59:56 → 4초 경과 > default 3초 임계 → stale
        val stale = Instant.parse("2026-06-07T00:59:56Z")
        assertEquals(EntryPriceDecision.Skip.QuoteStale, EntryPricePolicy.evaluate(input(quoteAsOf = stale)))
    }

    @Test
    fun `quote exactly at threshold age is fresh (boundary)`() {
        // 정확히 3초 경과(= 임계). > 비교라 임계값은 통과(fresh).
        val atThreshold = Instant.parse("2026-06-07T00:59:57Z") // 3초 전
        val d = EntryPricePolicy.evaluate(input(quoteAsOf = atThreshold))
        assertTrue(d is EntryPriceDecision.Enter)
    }

    @Test
    fun `future quote timestamp (clock skew) is not stale`() {
        // quoteAsOf가 evalAt보다 미래 → 음수 age → stale 아님(보수: 시계 skew 통과)
        val future = Instant.parse("2026-06-07T01:00:05Z")
        val d = EntryPricePolicy.evaluate(input(quoteAsOf = future))
        assertTrue(d is EntryPriceDecision.Enter)
    }

    @Test
    fun `skips SpreadTooWide when spread exceeds maxSpreadPct`() {
        // ask 10100, bid 10000, mid 10050 → spread = 100/10050 × 100 ≈ 0.995% > 0.3% → skip
        // 단 cap 검사 전에 스프레드가 먼저 걸려야 함(판정 순서 LOCK)
        val d = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10100", bid1 = "10000", maxSpreadPct = "0.3"))
        assertEquals(EntryPriceDecision.Skip.SpreadTooWide, d)
        assertEquals("SIGNAL_SKIPPED_SPREAD", (d as EntryPriceDecision.Skip).auditCode)
    }

    @Test
    fun `spread gate precedes cap — wide spread skips even if ask within cap`() {
        // ask 10010(cap 이내), bid 9900 → spread = 110/9955 ≈ 1.1% > 0.3% → SpreadTooWide(캡 아님)
        val d = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10010", bid1 = "9900", maxSpreadPct = "0.3"))
        assertEquals(EntryPriceDecision.Skip.SpreadTooWide, d)
    }

    @Test
    fun `skips PriceCapExceeded when ask1 above cap`() {
        // 기준가 10000, slippage 0.2% → cap 10020. ask1 10030 > cap → skip. spread는 좁게(bid 10025) 두어 통과
        val d = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10030", bid1 = "10025", maxSpreadPct = "0.3"))
        assertEquals(EntryPriceDecision.Skip.PriceCapExceeded, d)
        assertEquals("SIGNAL_SKIPPED_ENTRY_PRICE_CAP", (d as EntryPriceDecision.Skip).auditCode)
    }

    @Test
    fun `boundary — ask1 exactly equals cap enters`() {
        // 기준가 10000, slippage 0.2% → rawCap 10020 → 호가단위 10원 정렬 = 10020. ask1 == 10020 → Enter(<=)
        val d = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10020", bid1 = "10010", maxSpreadPct = "0.3"))
        assertTrue(d is EntryPriceDecision.Enter)
        assertEquals(BigDecimal("10020"), (d as EntryPriceDecision.Enter).limitPrice)
    }

    @Test
    fun `cap is floored to KRX tick — fractional cap does not lift ask above cap`() {
        // 기준가 10000, slippage 0.15% → rawCap 10015 → 10원 단위 floor = 10010.
        // ask1 10015(>cap 10010) → PriceCapExceeded (raw cap 10015였다면 통과했을 것 — floor가 자본 보호)
        val d =
            EntryPricePolicy.evaluate(
                input(priceAtSignal = "10000", ask1 = "10015", bid1 = "10010", maxEntrySlippagePct = "0.15", maxSpreadPct = "0.3"),
            )
        assertEquals(EntryPriceDecision.Skip.PriceCapExceeded, d)
    }

    @Test
    fun `retry keeps priceAtSignal fixed — same decision regardless of attempt`() {
        // attempt만 다르고 priceAtSignal 동일 → cap·판정 불변(추격 금지 §3-2)
        val d0 = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10010", bid1 = "10000", attempt = 0))
        val d3 = EntryPricePolicy.evaluate(input(priceAtSignal = "10000", ask1 = "10010", bid1 = "10000", attempt = 3))
        assertEquals(d0, d3)
        assertTrue(d0 is EntryPriceDecision.Enter)
    }

    @Test
    fun `determinism — same input yields same output`() {
        val i = input()
        assertEquals(EntryPricePolicy.evaluate(i), EntryPricePolicy.evaluate(i))
    }

    @Test
    fun `take-profit limit price = avg × (1+pct) floored to tick`() {
        // 평단 10000, 익절 3% → raw 10300 → 10원 단위 floor = 10300
        assertEquals(BigDecimal("10300"), EntryPricePolicy.takeProfitLimitPrice(BigDecimal("10000"), BigDecimal("3")))
    }

    @Test
    fun `take-profit limit price floors to tick on fractional result`() {
        // 평단 49000, 익절 2% → raw 49980 → 49000~49999 구간은 50원 단위 → floor 49950
        assertEquals(BigDecimal("49950"), EntryPricePolicy.takeProfitLimitPrice(BigDecimal("49000"), BigDecimal("2")))
    }
}
