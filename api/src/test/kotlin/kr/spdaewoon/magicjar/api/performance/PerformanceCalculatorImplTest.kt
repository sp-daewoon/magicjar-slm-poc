package kr.spdaewoon.magicjar.api.performance

import kr.spdaewoon.magicjar.api.service.performance.PerformanceCalculatorImpl
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * PerformanceCalculatorImplTest — 7건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. 총 P&L 합산
 * 2. 승률 계산
 * 3. 평균 손익비 (PLR)
 * 4. MDD peak-trough 산정
 * 5. MDD edge case — 단조증가 (MDD=0%)
 * 6. 빈 거래 목록
 * 7. Equity Curve 시계열 생성
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class PerformanceCalculatorImplTest {
    private val sut = PerformanceCalculatorImpl()

    private fun pnl(
        date: LocalDate,
        realizedPnl: BigDecimal,
        winCount: Int = 0,
        lossCount: Int = 0,
        tradeCount: Int = 0,
        avgProfit: BigDecimal? = null,
        avgLoss: BigDecimal? = null,
    ) = PnlDaily(
        userId = "user_test",
        tradeDate = date,
        realizedPnl = realizedPnl,
        winCount = winCount,
        lossCount = lossCount,
        tradeCount = tradeCount,
        avgProfit = avgProfit,
        avgLoss = avgLoss,
        equityAtClose = null,
    )

    private val d1 = LocalDate.of(2026, 5, 1)
    private val d2 = LocalDate.of(2026, 5, 2)
    private val d3 = LocalDate.of(2026, 5, 5)

    @Test
    fun `총 P&L 합산 — 양수+음수 합산 정합`() {
        val list =
            listOf(
                pnl(d1, BigDecimal("500000")),
                pnl(d2, BigDecimal("-200000")),
                pnl(d3, BigDecimal("950000")),
            )
        val result = sut.totalPnl(list)
        assertThat(result).isEqualByComparingTo(BigDecimal("1250000"))
    }

    @Test
    fun `승률 계산 — 10 거래 중 7 수익`() {
        val list =
            listOf(
                pnl(d1, BigDecimal("300000"), winCount = 7, lossCount = 3, tradeCount = 10),
            )
        val result = sut.winRate(list)
        assertThat(result).isEqualByComparingTo(BigDecimal("70.00"))
    }

    @Test
    fun `평균 손익비 계산 — avgProfit=150000 avgLoss=-50000`() {
        val list =
            listOf(
                pnl(
                    d1,
                    BigDecimal("100000"),
                    winCount = 2,
                    lossCount = 1,
                    tradeCount = 3,
                    avgProfit = BigDecimal("150000"),
                    avgLoss = BigDecimal("-50000"),
                ),
            )
        val result = sut.profitLossRatio(list)
        assertThat(result).isNotNull
        assertThat(result).isEqualByComparingTo(BigDecimal("3.0000"))
    }

    @Test
    fun `MDD peak-trough 산정 — peak 1000000 trough 600000 MDD=40%`() {
        val points =
            sut.buildEquityCurve(
                listOf(
                    pnl(d1, BigDecimal("500000"), tradeCount = 1),
                    pnl(d2, BigDecimal("500000"), tradeCount = 1),
                    pnl(d3, BigDecimal("-400000"), tradeCount = 1),
                ),
                d1,
                d3,
            )
        val mdd = sut.maxDrawdown(points)
        // peak=1000000, trough=600000, mdd=(1000000-600000)/1000000*100=40%
        assertThat(mdd).isEqualByComparingTo(BigDecimal("40.0000"))
    }

    @Test
    fun `MDD edge case — 단조증가 MDD=0%`() {
        val points =
            sut.buildEquityCurve(
                listOf(
                    pnl(d1, BigDecimal("100000"), tradeCount = 1),
                    pnl(d2, BigDecimal("200000"), tradeCount = 1),
                    pnl(d3, BigDecimal("300000"), tradeCount = 1),
                ),
                d1,
                d3,
            )
        val mdd = sut.maxDrawdown(points)
        assertThat(mdd).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `빈 거래 목록 — 모든 지표 0 또는 null 반환`() {
        val empty = emptyList<PnlDaily>()
        assertThat(sut.totalPnl(empty)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(sut.winRate(empty)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(sut.profitLossRatio(empty)).isNull()
        assertThat(sut.maxDrawdown(emptyList())).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `Equity Curve 시계열 생성 — 누적 P&L monotonic 증가 검증`() {
        val list =
            listOf(
                pnl(d1, BigDecimal("100000"), tradeCount = 1),
                pnl(d2, BigDecimal("50000"), tradeCount = 1),
                pnl(d3, BigDecimal("200000"), tradeCount = 1),
            )
        val curve = sut.buildEquityCurve(list, d1, d3)
        assertThat(curve).hasSize(3)
        assertThat(curve[0].cumulativePnl).isEqualByComparingTo(BigDecimal("100000"))
        assertThat(curve[1].cumulativePnl).isEqualByComparingTo(BigDecimal("150000"))
        assertThat(curve[2].cumulativePnl).isEqualByComparingTo(BigDecimal("350000"))
    }
}
