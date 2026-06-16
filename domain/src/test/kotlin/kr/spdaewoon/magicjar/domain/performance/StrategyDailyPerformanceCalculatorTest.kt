package kr.spdaewoon.magicjar.domain.performance

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.order.OrderSide
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * StrategyDailyPerformanceCalculator 단위 테스트 — spec §2·§4-1 왕복 집계 산식 검증.
 *
 * 핵심: 평균단가 replay·왕복 마감(수량 0 복귀)·KST 청산일 귀속·롱 온리 방어·수익률 산식.
 */
class StrategyDailyPerformanceCalculatorTest {
    private val kst = ZoneId.of("Asia/Seoul")

    /** KST 로컬 일시 → UTC Instant (체결 입력 헬퍼). */
    private fun atKst(
        date: String,
        time: String = "10:00",
    ) = LocalDateTime.parse("${date}T$time:00").atZone(kst).toInstant()

    private fun exec(
        strategyId: Long = 1L,
        code: String = "032500",
        side: OrderSide,
        quantity: Long,
        price: String,
        at: java.time.Instant,
    ) = ExecutionRecord(strategyId, code, side, quantity, BigDecimal(price), at)

    @Test
    fun `같은 종목 3왕복 — roundTripCount 3, 손익·수익률 집계 (후성 시나리오)`() {
        // 후성(032500) 같은 날 3왕복: +1000, -500, +2000 (수량·가격은 단순화)
        val day = "2026-06-16"
        val executions =
            listOf(
                // 왕복1: 100주 @1000 매수 → @1010 매도 = +1000, cost 100000
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "09:01")),
                exec(side = OrderSide.SELL, quantity = 100, price = "1010", at = atKst(day, "09:02")),
                // 왕복2: 50주 @2000 매수 → @1990 매도 = -500, cost 100000
                exec(side = OrderSide.BUY, quantity = 50, price = "2000", at = atKst(day, "10:01")),
                exec(side = OrderSide.SELL, quantity = 50, price = "1990", at = atKst(day, "10:02")),
                // 왕복3: 100주 @1000 매수 → @1020 매도 = +2000, cost 100000
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "11:01")),
                exec(side = OrderSide.SELL, quantity = 100, price = "1020", at = atKst(day, "11:02")),
            )

        val rows = StrategyDailyPerformanceCalculator.aggregate(executions)

        rows shouldHaveSize 1
        val row = rows.single()
        row.strategyId shouldBe 1L
        row.tradeDate shouldBe LocalDate.parse(day)
        row.roundTripCount shouldBe 3
        row.winCount shouldBe 2
        row.lossCount shouldBe 1
        row.realizedPnl shouldBe BigDecimal("2500.0000") // +1000 -500 +2000
        row.totalCostBasis shouldBe BigDecimal("300000.0000") // 100000 × 3
        // 2500 / 300000 × 100 = 0.8333
        row.returnPct shouldBe BigDecimal("0.8333")
    }

    @Test
    fun `며칠 걸친 왕복 — 청산일(수요일)에 귀속`() {
        // 월요일 매수, 수요일 매도 → 수요일 행
        val executions =
            listOf(
                exec(side = OrderSide.BUY, quantity = 10, price = "5000", at = atKst("2026-06-15", "14:00")), // 월
                exec(side = OrderSide.SELL, quantity = 10, price = "5500", at = atKst("2026-06-17", "10:00")), // 수
            )

        val rows = StrategyDailyPerformanceCalculator.aggregate(executions)

        rows shouldHaveSize 1
        rows.single().tradeDate shouldBe LocalDate.parse("2026-06-17")
        rows.single().realizedPnl shouldBe BigDecimal("5000.0000") // (5500-5000)*10
        rows.single().costBasis() shouldBe BigDecimal("50000.0000")
    }

    @Test
    fun `분할 매수 후 분할 매도 — 평균단가 정확성`() {
        // 100주 @1000 + 100주 @2000 → 평균 1500. 100주 @1800 매도 + 100주 @1600 매도.
        // 왕복 마감 시점 = 두 번째 매도(수량 0 복귀).
        // pnl = (1800-1500)*100 + (1600-1500)*100 = 30000 + 10000 = 40000
        // cost = 1500*100 + 1500*100 = 300000
        val day = "2026-06-16"
        val executions =
            listOf(
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "09:00")),
                exec(side = OrderSide.BUY, quantity = 100, price = "2000", at = atKst(day, "09:30")),
                exec(side = OrderSide.SELL, quantity = 100, price = "1800", at = atKst(day, "10:00")),
                exec(side = OrderSide.SELL, quantity = 100, price = "1600", at = atKst(day, "10:30")),
            )

        val trips = StrategyDailyPerformanceCalculator.extractRoundTrips(executions)
        trips shouldHaveSize 1
        trips.single().realizedPnl shouldBe BigDecimal("40000.0000")
        trips.single().costBasis shouldBe BigDecimal("300000.0000")

        val rows = StrategyDailyPerformanceCalculator.aggregate(executions)
        rows.single().roundTripCount shouldBe 1
        rows.single().winCount shouldBe 1
    }

    @Test
    fun `부분 매도는 왕복 미마감 — 수량 0 복귀 시점에만 1왕복`() {
        // 100주 매수 → 60주 매도(미마감) → 40주 매도(마감)
        val day = "2026-06-16"
        val executions =
            listOf(
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "09:00")),
                exec(side = OrderSide.SELL, quantity = 60, price = "1100", at = atKst(day, "10:00")),
                exec(side = OrderSide.SELL, quantity = 40, price = "1200", at = atKst(day, "11:00")),
            )

        val trips = StrategyDailyPerformanceCalculator.extractRoundTrips(executions)
        trips shouldHaveSize 1
        // pnl = (1100-1000)*60 + (1200-1000)*40 = 6000 + 8000 = 14000, cost = 1000*100 = 100000
        trips.single().realizedPnl shouldBe BigDecimal("14000.0000")
        trips.single().costBasis shouldBe BigDecimal("100000.0000")
    }

    @Test
    fun `win·loss·breakeven 카운트 — breakeven 은 win·loss 제외, roundTripCount 포함`() {
        val day = "2026-06-16"
        val executions =
            listOf(
                // win: +1000
                exec(side = OrderSide.BUY, quantity = 10, price = "1000", at = atKst(day, "09:00")),
                exec(side = OrderSide.SELL, quantity = 10, price = "1100", at = atKst(day, "09:10")),
                // breakeven: 0
                exec(side = OrderSide.BUY, quantity = 10, price = "2000", at = atKst(day, "10:00")),
                exec(side = OrderSide.SELL, quantity = 10, price = "2000", at = atKst(day, "10:10")),
                // loss: -500
                exec(side = OrderSide.BUY, quantity = 10, price = "3000", at = atKst(day, "11:00")),
                exec(side = OrderSide.SELL, quantity = 10, price = "2950", at = atKst(day, "11:10")),
            )

        val row = StrategyDailyPerformanceCalculator.aggregate(executions).single()
        row.roundTripCount shouldBe 3
        row.winCount shouldBe 1
        row.lossCount shouldBe 1
        row.realizedPnl shouldBe BigDecimal("500.0000") // 1000 + 0 - 500
    }

    @Test
    fun `롱 온리 방어 — 보유 초과 매도는 보유분까지만 청산, 음수 포지션 금지`() {
        // 100주 보유 → 150주 매도 시도. 100주만 청산하고 50주는 버림.
        val day = "2026-06-16"
        val executions =
            listOf(
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "09:00")),
                exec(side = OrderSide.SELL, quantity = 150, price = "1100", at = atKst(day, "10:00")),
            )

        val trips = StrategyDailyPerformanceCalculator.extractRoundTrips(executions)
        trips shouldHaveSize 1
        // 100주만 청산: (1100-1000)*100 = 10000
        trips.single().realizedPnl shouldBe BigDecimal("10000.0000")
        trips.single().costBasis shouldBe BigDecimal("100000.0000")
    }

    @Test
    fun `보유 없는 매도(공매도성 비정상) — 무시`() {
        val day = "2026-06-16"
        val executions =
            listOf(
                exec(side = OrderSide.SELL, quantity = 50, price = "1000", at = atKst(day, "09:00")),
            )
        StrategyDailyPerformanceCalculator.aggregate(executions) shouldHaveSize 0
    }

    @Test
    fun `전략·종목별 독립 replay — 서로 다른 strategyId 는 별도 행`() {
        val day = "2026-06-16"
        val executions =
            listOf(
                exec(strategyId = 1L, code = "AAA", side = OrderSide.BUY, quantity = 10, price = "1000", at = atKst(day, "09:00")),
                exec(strategyId = 1L, code = "AAA", side = OrderSide.SELL, quantity = 10, price = "1100", at = atKst(day, "09:10")),
                exec(strategyId = 2L, code = "AAA", side = OrderSide.BUY, quantity = 10, price = "1000", at = atKst(day, "09:00")),
                exec(strategyId = 2L, code = "AAA", side = OrderSide.SELL, quantity = 10, price = "900", at = atKst(day, "09:10")),
            )

        val rows = StrategyDailyPerformanceCalculator.aggregate(executions)
        rows shouldHaveSize 2
        rows.first { it.strategyId == 1L }.realizedPnl shouldBe BigDecimal("1000.0000")
        rows.first { it.strategyId == 2L }.realizedPnl shouldBe BigDecimal("-1000.0000")
    }

    @Test
    fun `입력 순서가 뒤섞여도 executedAt 기준 정렬 replay (결정론)`() {
        val day = "2026-06-16"
        val shuffled =
            listOf(
                exec(side = OrderSide.SELL, quantity = 100, price = "1010", at = atKst(day, "09:02")),
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "09:01")),
            )
        val rows = StrategyDailyPerformanceCalculator.aggregate(shuffled)
        rows.single().realizedPnl shouldBe BigDecimal("1000.0000")
        rows.single().roundTripCount shouldBe 1
    }

    @Test
    fun `진입금액 0 불가능 — 정상 왕복은 항상 returnPct 산출`() {
        // costBasis = 0 인 경우는 정상 매수 없이 마감될 수 없으므로 returnPct 는 항상 non-null.
        // 빈 입력은 행 0건.
        StrategyDailyPerformanceCalculator.aggregate(emptyList()).shouldHaveSize(0)
    }

    @Test
    fun `미청산 보유(매수만) — 왕복 0건`() {
        val day = "2026-06-16"
        val executions =
            listOf(
                exec(side = OrderSide.BUY, quantity = 100, price = "1000", at = atKst(day, "09:00")),
            )
        StrategyDailyPerformanceCalculator.extractRoundTrips(executions).shouldHaveSize(0)
        StrategyDailyPerformanceCalculator.aggregate(executions).shouldHaveSize(0)
    }

    @Test
    fun `여러 날 왕복 — 날짜별 행 분리 + 정렬`() {
        val executions =
            listOf(
                // 6/17 왕복
                exec(side = OrderSide.BUY, quantity = 10, price = "1000", at = atKst("2026-06-17", "09:00")),
                exec(side = OrderSide.SELL, quantity = 10, price = "1100", at = atKst("2026-06-17", "09:10")),
                // 6/16 왕복
                exec(side = OrderSide.BUY, quantity = 10, price = "1000", at = atKst("2026-06-16", "09:00")),
                exec(side = OrderSide.SELL, quantity = 10, price = "1050", at = atKst("2026-06-16", "09:10")),
            )
        val rows = StrategyDailyPerformanceCalculator.aggregate(executions)
        rows shouldHaveSize 2
        // tradeDate 오름차순 정렬
        rows[0].tradeDate shouldBe LocalDate.parse("2026-06-16")
        rows[1].tradeDate shouldBe LocalDate.parse("2026-06-17")
    }

    @Test
    fun `KST 자정 직전 매도 — 같은 KST 일자 귀속 (UTC 환산 오귀속 방지)`() {
        // KST 23:30 매도 → UTC 14:30 같은 날. KST 일자로 귀속돼야 함.
        val executions =
            listOf(
                exec(side = OrderSide.BUY, quantity = 10, price = "1000", at = atKst("2026-06-16", "23:00")),
                exec(side = OrderSide.SELL, quantity = 10, price = "1100", at = atKst("2026-06-16", "23:30")),
            )
        StrategyDailyPerformanceCalculator.aggregate(executions).single().tradeDate shouldBe LocalDate.parse("2026-06-16")
    }

    // RoundTrip.costBasis 접근 헬퍼 (StrategyDailyPerformance 와 혼동 방지용 — 가독성)
    private fun StrategyDailyPerformance.costBasis() = totalCostBasis
}
