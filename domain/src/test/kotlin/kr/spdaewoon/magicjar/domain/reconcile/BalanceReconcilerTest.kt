package kr.spdaewoon.magicjar.domain.reconcile

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * BalanceReconciler 단위 테스트 (ADR 0070 §2-10 임계 LOCK 검증).
 *
 * 6 케이스 (spec §11-1 5케이스 + 종합):
 *  (a) 정상 — KIS·내부 모두 일치 → Reconciled
 *  (b) 수량 diff 1주 → Drifted (자본 사고 — KillSwitch 트리거)
 *  (c) 평균단가 diff 0.5% → PriceDrifted (audit only)
 *  (d) 종목 누락 (KIS 있는데 내부 0주) → Drifted
 *  (e) 반대 누락 (내부 있는데 KIS 0주) → Drifted (매도 미반영 의심)
 *  (f) 다중 종목 — 수량 drift + 평균단가 drift 동시 → Drifted (priceDrift는 부수)
 *
 * AI 호출 0 — 순수 함수 테스트.
 */
class BalanceReconcilerTest {
    @Test
    fun `(a) 정상 흐름 — 모든 종목 수량·평균단가 일치 시 Reconciled`() {
        val kis =
            listOf(
                ReconcileHolding("005930", 100, BigDecimal("70000")),
                ReconcileHolding("000660", 50, BigDecimal("130000")),
            )
        val internal =
            listOf(
                InternalHolding("005930", 100, BigDecimal("70000")),
                InternalHolding("000660", 50, BigDecimal("130000")),
            )

        val result = BalanceReconciler.compare(kis, internal)

        result.shouldBeInstanceOf<ReconcileResult.Reconciled>()
        result.totalHoldings shouldBe 2
    }

    @Test
    fun `(b) 수량 diff 1주 — Drifted (자본 사고 임계 LOCK)`() {
        val kis = listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        val internal = listOf(InternalHolding("005930", 99, BigDecimal("70000")))

        val result = BalanceReconciler.compare(kis, internal)

        result.shouldBeInstanceOf<ReconcileResult.Drifted>()
        val drift = result.driftedHoldings.first()
        drift.symbol shouldBe "005930"
        drift.qtyDiff shouldBe 1
        drift.isDrift shouldBe true
    }

    @Test
    fun `(c) 평균단가 diff 0_5퍼 — PriceDrifted (audit only, KillSwitch 미트리거)`() {
        // 70000 → 70350 = 0.5% diff (정확히 임계)
        val kis = listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        val internal = listOf(InternalHolding("005930", 100, BigDecimal("70350")))

        val result = BalanceReconciler.compare(kis, internal)

        result.shouldBeInstanceOf<ReconcileResult.PriceDrifted>()
        val priceDiff = result.priceDriftedHoldings.first()
        priceDiff.symbol shouldBe "005930"
        priceDiff.isPriceDrift shouldBe true
        priceDiff.isDrift shouldBe false // 수량 drift 미트리거
    }

    @Test
    fun `(d) 종목 누락 — KIS 있는데 내부 0주 시 Drifted`() {
        val kis = listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        val internal = emptyList<InternalHolding>()

        val result = BalanceReconciler.compare(kis, internal)

        result.shouldBeInstanceOf<ReconcileResult.Drifted>()
        val drift = result.driftedHoldings.first()
        drift.symbol shouldBe "005930"
        drift.kisQty shouldBe 100
        drift.internalQty shouldBe 0
        drift.qtyDiff shouldBe 100
    }

    @Test
    fun `(e) 반대 누락 — 내부 있는데 KIS 0주 시 Drifted (매도 미반영 의심)`() {
        val kis = emptyList<ReconcileHolding>()
        val internal = listOf(InternalHolding("005930", 50, BigDecimal("70000")))

        val result = BalanceReconciler.compare(kis, internal)

        result.shouldBeInstanceOf<ReconcileResult.Drifted>()
        val drift = result.driftedHoldings.first()
        drift.symbol shouldBe "005930"
        drift.kisQty shouldBe 0
        drift.internalQty shouldBe 50
        drift.qtyDiff shouldBe -50
    }

    @Test
    fun `(f) 다중 종목 — 수량 drift + priceDrift 동시 — Drifted (priceDrift는 부수)`() {
        val kis =
            listOf(
                ReconcileHolding("005930", 100, BigDecimal("70000")), // 수량 drift
                ReconcileHolding("000660", 50, BigDecimal("130000")), // priceDrift만
            )
        val internal =
            listOf(
                InternalHolding("005930", 99, BigDecimal("70000")),
                InternalHolding("000660", 50, BigDecimal("131000")), // 0.77% diff
            )

        val result = BalanceReconciler.compare(kis, internal)

        result.shouldBeInstanceOf<ReconcileResult.Drifted>()
        result.driftedHoldings shouldHaveSize 1
        result.driftedHoldings.first().symbol shouldBe "005930"
        result.priceDriftedHoldings shouldHaveSize 1
        result.priceDriftedHoldings.first().symbol shouldBe "000660"
    }
}
