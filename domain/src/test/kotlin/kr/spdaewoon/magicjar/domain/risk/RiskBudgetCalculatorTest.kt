package kr.spdaewoon.magicjar.domain.risk

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.position.Position
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * RiskBudgetCalculator 단위 테스트 — 매수 사이징(PR #1011)·사용량 표시 단일 진실원 검증.
 *
 * 핵심: 본 계산기는 consumer RiskGatewayEvaluator.resolveBuyBudget 가 위임하는 동일 함수이므로,
 * 여기 검증한 산식이 곧 게이트 실제 판정값이자 api 사용량 표시값이다(표시값 == 실제판정 보장).
 */
class RiskBudgetCalculatorTest {
    private val now = Instant.fromEpochSeconds(1_736_640_000L)

    private fun config(
        maxPositionAmount: BigDecimal = BigDecimal("10000000"),
        maxPositionPercent: BigDecimal = BigDecimal("10.00"),
        dllAmount: BigDecimal? = null,
        dllPercent: BigDecimal? = null,
    ): RiskConfig =
        RiskConfig(
            userId = "u1",
            maxPositionAmount = maxPositionAmount,
            maxPositionPercent = maxPositionPercent,
            dailyLossLimitAmount = dllAmount,
            dailyLossLimitPercent = dllPercent,
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = now,
            updatedAt = now,
        )

    private fun position(
        code: String,
        qty: Int,
        avg: BigDecimal,
    ): Position = Position(userId = "u1", code = code, quantity = qty, avgPrice = avg, updatedAt = now)

    // ── totalExposure ───────────────────────────────────────────────────────────

    @Test
    fun `totalExposure 는 전 종목 avgPrice x quantity 합(매수원가)`() {
        val positions =
            listOf(
                position("005930", 10, BigDecimal("70000")), // 700,000
                position("000660", 5, BigDecimal("100000")), // 500,000
            )
        RiskBudgetCalculator.totalExposure(positions) shouldBe BigDecimal("1200000")
    }

    @Test
    fun `totalExposure 는 quantity 0 포지션(청산 행)을 제외한다`() {
        val positions =
            listOf(
                position("005930", 10, BigDecimal("70000")),
                position("000660", 0, BigDecimal("100000")), // 청산 — 제외
            )
        RiskBudgetCalculator.totalExposure(positions) shouldBe BigDecimal("700000")
    }

    // ── resolveBuyBudget — DTO 예시값 1:1 재현 ───────────────────────────────────

    @Test
    fun `resolveBuyBudget DTO 예시 — 절대1천만·비율10퍼·순자산1억·노출900만·예수금300만 → buyable 100만`() {
        // DTO 계약 예시값 그대로 재현 (frontend 계약 LOCK).
        val budget =
            RiskBudgetCalculator.resolveBuyBudget(
                config = config(maxPositionAmount = BigDecimal("10000000"), maxPositionPercent = BigDecimal("10.0")),
                currentCapital = BigDecimal("100000000"), // 순자산 1억
                orderableCash = BigDecimal("3000000"), // 예수금 300만
                positions = listOf(position("005930", 90, BigDecimal("100000"))), // 노출 900만
            )

        budget.absoluteLimit shouldBe BigDecimal("10000000")
        budget.percentLimit shouldBe BigDecimal("10000000") // 1억 × 10% = 1천만
        budget.currentCapital shouldBe BigDecimal("100000000")
        budget.totalLimit shouldBe BigDecimal("10000000") // min(1천만, 1천만)
        budget.totalExposure shouldBe BigDecimal("9000000") // 90 × 10만
        budget.remainingLimit shouldBe BigDecimal("1000000") // 1천만 − 900만
        budget.orderableCash shouldBe BigDecimal("3000000")
        budget.buyable shouldBe BigDecimal("1000000") // min(100만, 300만)
    }

    @Test
    fun `resolveBuyBudget — 예수금이 남은한도보다 작으면 예수금이 매수가능 cap`() {
        val budget =
            RiskBudgetCalculator.resolveBuyBudget(
                config = config(maxPositionAmount = BigDecimal("10000000"), maxPositionPercent = BigDecimal("100.00")),
                currentCapital = BigDecimal("100000000"),
                orderableCash = BigDecimal("500000"), // 예수금 50만 (한도보다 작음)
                positions = emptyList(),
            )
        budget.remainingLimit shouldBe BigDecimal("10000000")
        budget.buyable shouldBe BigDecimal("500000") // min(1천만, 50만) = 예수금
    }

    @Test
    fun `resolveBuyBudget — 비율한도가 절대한도보다 작으면 비율한도가 총한도`() {
        val budget =
            RiskBudgetCalculator.resolveBuyBudget(
                config = config(maxPositionAmount = BigDecimal("10000000"), maxPositionPercent = BigDecimal("5.00")),
                currentCapital = BigDecimal("100000000"), // 5% = 500만 < 절대 1천만
                orderableCash = BigDecimal("10000000"),
                positions = emptyList(),
            )
        budget.percentLimit shouldBe BigDecimal("5000000")
        budget.totalLimit shouldBe BigDecimal("5000000") // min(1천만, 500만)
        budget.buyable shouldBe BigDecimal("5000000")
    }

    @Test
    fun `resolveBuyBudget — 노출이 총한도 초과면 남은한도·매수가능 0(음수 방지)`() {
        val budget =
            RiskBudgetCalculator.resolveBuyBudget(
                config = config(maxPositionAmount = BigDecimal("10000000"), maxPositionPercent = BigDecimal("100.00")),
                currentCapital = BigDecimal("100000000"),
                orderableCash = BigDecimal("5000000"),
                positions = listOf(position("005930", 150, BigDecimal("100000"))), // 노출 1500만 > 한도 1천만
            )
        budget.remainingLimit shouldBe BigDecimal.ZERO
        budget.buyable shouldBe BigDecimal.ZERO
    }

    @Test
    fun `resolveBuyBudget — 음수 예수금은 0 으로 정규화`() {
        val budget =
            RiskBudgetCalculator.resolveBuyBudget(
                config = config(),
                currentCapital = BigDecimal("100000000"),
                orderableCash = BigDecimal("-1000"),
                positions = emptyList(),
            )
        budget.orderableCash shouldBe BigDecimal.ZERO
        budget.buyable shouldBe BigDecimal.ZERO
    }

    // ── dailyLossRemaining ───────────────────────────────────────────────────────

    @Test
    fun `dailyLossRemaining amount — 한도10만·당일손익 -3만2천 → 여유 6만8천`() {
        val remaining =
            RiskBudgetCalculator.dailyLossRemaining(
                config = config(dllAmount = BigDecimal("100000")),
                currentCapital = BigDecimal("100000000"),
                todayRealizedPnl = BigDecimal("-32000"),
            )
        remaining shouldBe BigDecimal("68000")
    }

    @Test
    fun `dailyLossRemaining amount — 손실이 한도 도달·초과면 0`() {
        val remaining =
            RiskBudgetCalculator.dailyLossRemaining(
                config = config(dllAmount = BigDecimal("100000")),
                currentCapital = BigDecimal("100000000"),
                todayRealizedPnl = BigDecimal("-150000"), // 한도 초과
            )
        remaining shouldBe BigDecimal.ZERO
    }

    @Test
    fun `dailyLossRemaining percent — 순자산100만·3퍼·당일 -1만 → 한도3만 여유2만`() {
        val remaining =
            RiskBudgetCalculator.dailyLossRemaining(
                config = config(dllPercent = BigDecimal("3.00")),
                currentCapital = BigDecimal("1000000"), // 3% = 3만
                todayRealizedPnl = BigDecimal("-10000"),
            )
        remaining shouldBe BigDecimal("20000")
    }

    @Test
    fun `dailyLossRemaining percent — capital 0 이면 환산 불가 null`() {
        val remaining =
            RiskBudgetCalculator.dailyLossRemaining(
                config = config(dllPercent = BigDecimal("3.00")),
                currentCapital = BigDecimal.ZERO,
                todayRealizedPnl = BigDecimal("-10000"),
            )
        remaining shouldBe null
    }

    @Test
    fun `dailyLossRemaining — DLL 비활성(둘 다 null)이면 null`() {
        val remaining =
            RiskBudgetCalculator.dailyLossRemaining(
                config = config(dllAmount = null, dllPercent = null),
                currentCapital = BigDecimal("100000000"),
                todayRealizedPnl = BigDecimal("-10000"),
            )
        remaining shouldBe null
    }
}
