package kr.spdaewoon.magicjar.domain.performance

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * StrategyConfidenceCalculator 단위 테스트 — spec §4-5 신뢰도 라벨·통계 검증.
 *
 * 라벨 경계: 누적왕복<10 OR 거래일<5 → LOW, <30 → MEDIUM, else HIGH.
 */
class StrategyConfidenceCalculatorTest {
    private fun row(
        date: String,
        roundTrips: Int,
        returnPct: String?,
    ) = StrategyDailyPerformance(
        strategyId = 1L,
        tradeDate = LocalDate.parse(date),
        roundTripCount = roundTrips,
        winCount = 0,
        lossCount = 0,
        realizedPnl = BigDecimal.ZERO,
        totalCostBasis = if (returnPct == null) BigDecimal.ZERO else BigDecimal("100000"),
        returnPct = returnPct?.let { BigDecimal(it) },
    )

    /** N 일자, 각 1왕복, returnPct 0.5 — 표본수만 조절하는 헬퍼. */
    private fun nDays(
        days: Int,
        tripsPerDay: Int = 1,
    ): List<StrategyDailyPerformance> =
        (1..days).map { d ->
            row(LocalDate.of(2026, 1, 1).plusDays((d - 1).toLong()).toString(), tripsPerDay, "0.5")
        }

    @Test
    fun `LOW — 누적 왕복 부족 (9건)`() {
        // 9일 × 1왕복 = 9 < 10 → LOW (거래일 9 ≥ 5 이지만 왕복 미달)
        val result = StrategyConfidenceCalculator.compute(nDays(9))
        result.label shouldBe ConfidenceLabel.LOW
        result.sampleRoundTrips shouldBe 9
        result.tradingDays shouldBe 9
    }

    @Test
    fun `LOW — 거래일 부족 (4일, 왕복 충분)`() {
        // 4일 × 3왕복 = 12 ≥ 10 이지만 거래일 4 < 5 → LOW
        val result = StrategyConfidenceCalculator.compute(nDays(4, tripsPerDay = 3))
        result.label shouldBe ConfidenceLabel.LOW
        result.sampleRoundTrips shouldBe 12
        result.tradingDays shouldBe 4
    }

    @Test
    fun `MEDIUM 경계 — 왕복 10 거래일 5 (LOW 탈출 최소)`() {
        // 5일 × 2왕복 = 10 ≥ 10, 거래일 5 ≥ 5, 10 < 30 → MEDIUM
        val result = StrategyConfidenceCalculator.compute(nDays(5, tripsPerDay = 2))
        result.label shouldBe ConfidenceLabel.MEDIUM
        result.sampleRoundTrips shouldBe 10
        result.tradingDays shouldBe 5
    }

    @Test
    fun `MEDIUM — 왕복 29건`() {
        // 29일 × 1왕복 = 29 < 30 → MEDIUM
        StrategyConfidenceCalculator.compute(nDays(29)).label shouldBe ConfidenceLabel.MEDIUM
    }

    @Test
    fun `HIGH 경계 — 왕복 30건`() {
        // 30일 × 1왕복 = 30 ≥ 30 → HIGH
        val result = StrategyConfidenceCalculator.compute(nDays(30))
        result.label shouldBe ConfidenceLabel.HIGH
        result.sampleRoundTrips shouldBe 30
    }

    @Test
    fun `빈 입력 — LOW, 통계 null`() {
        val result = StrategyConfidenceCalculator.compute(emptyList())
        result.label shouldBe ConfidenceLabel.LOW
        result.sampleRoundTrips shouldBe 0
        result.tradingDays shouldBe 0
        result.meanReturnPct.shouldBeNull()
        result.stdDevReturnPct.shouldBeNull()
        result.sharpe.shouldBeNull()
    }

    @Test
    fun `평균·표준편차·Sharpe 산식 검증`() {
        // returnPct: 1, 3, 5 → mean=3, pop std=sqrt(((4)+(0)+(4))/3)=sqrt(2.6667)=1.6330, sharpe=3/1.6330=1.8371
        val rows =
            listOf(
                row("2026-01-01", 5, "1"),
                row("2026-01-02", 5, "3"),
                row("2026-01-03", 5, "5"),
                row("2026-01-04", 5, "3"),
                row("2026-01-05", 5, "3"),
            )
        val result = StrategyConfidenceCalculator.compute(rows)
        // mean of [1,3,5,3,3] = 15/5 = 3
        result.meanReturnPct shouldBe BigDecimal("3.0000")
        // variance = ((2^2)+(0)+(2^2)+(0)+(0))/5 = 8/5 = 1.6 ; std = sqrt(1.6) = 1.2649
        result.stdDevReturnPct shouldBe BigDecimal("1.2649")
        // sharpe = 3 / 1.264911 = 2.3717
        result.sharpe shouldBe BigDecimal("2.3717")
    }

    @Test
    fun `표준편차 0 — sharpe null (모든 수익률 동일)`() {
        val rows =
            (1..6).map { row(LocalDate.of(2026, 1, it).toString(), 2, "2.5") }
        val result = StrategyConfidenceCalculator.compute(rows)
        result.meanReturnPct shouldBe BigDecimal("2.5000")
        result.stdDevReturnPct shouldBe BigDecimal("0.0000")
        result.sharpe.shouldBeNull()
        result.label shouldBe ConfidenceLabel.MEDIUM // 12 왕복, 6 거래일
    }

    @Test
    fun `표본 1개 — std·sharpe null (분산 무의미)`() {
        val result = StrategyConfidenceCalculator.compute(listOf(row("2026-01-01", 1, "4")))
        result.meanReturnPct shouldBe BigDecimal("4.0000")
        result.stdDevReturnPct.shouldBeNull()
        result.sharpe.shouldBeNull()
    }

    @Test
    fun `returnPct null 행(진입금액 0) — 수익률 통계 제외, 왕복·거래일 포함`() {
        // 5일 중 1일은 returnPct null (집계상 roundTrip 은 있으나 cost 0 가정)
        val rows =
            listOf(
                row("2026-01-01", 2, "1"),
                row("2026-01-02", 2, "3"),
                row("2026-01-03", 2, null),
                row("2026-01-04", 2, "5"),
                row("2026-01-05", 2, "3"),
            )
        val result = StrategyConfidenceCalculator.compute(rows)
        result.sampleRoundTrips shouldBe 10
        result.tradingDays shouldBe 5
        // mean of [1,3,5,3] = 12/4 = 3 (null 제외)
        result.meanReturnPct shouldBe BigDecimal("3.0000")
    }

    @Test
    fun `label 함수 직접 호출 — 임계치 const 노출 검증`() {
        StrategyConfidenceCalculator.label(StrategyConfidenceCalculator.MIN_ROUND_TRIPS_LOW - 1, 100) shouldBe ConfidenceLabel.LOW
        StrategyConfidenceCalculator.label(100, StrategyConfidenceCalculator.MIN_TRADING_DAYS_LOW - 1) shouldBe ConfidenceLabel.LOW
        StrategyConfidenceCalculator.label(StrategyConfidenceCalculator.MIN_ROUND_TRIPS_MEDIUM - 1, 5) shouldBe ConfidenceLabel.MEDIUM
        StrategyConfidenceCalculator.label(StrategyConfidenceCalculator.MIN_ROUND_TRIPS_MEDIUM, 5) shouldBe ConfidenceLabel.HIGH
    }
}
