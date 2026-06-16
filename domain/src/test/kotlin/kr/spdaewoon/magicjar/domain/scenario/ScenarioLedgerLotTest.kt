package kr.spdaewoon.magicjar.domain.scenario

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 분할매수 R3 — [ScenarioLedger] 가상 전략 lot(filledLots·executedExitStages) 추적 검증.
 *
 * 리플레이가 scale-in 회차 진입·평단 갱신·분할 익절 발화 단계를 추적해 [ScaleInExitCalculator] 입력으로
 * 넘길 수 있어야 한다. 단일 진입(filledLots 1로 수렴)은 기존 동작과 호환(zero-regression)임을 함께 확인한다.
 */
class ScenarioLedgerLotTest {
    private val t = Instant.parse("2026-06-02T07:15:00Z")

    @Test
    fun `scale-in buys accumulate filled lots and weighted average price`() {
        val ledger = ScenarioLedger(initialCash = BigDecimal("10000000"), initialPositions = emptyList())

        ledger.buy("005930", 50, BigDecimal("1000"), t)
        ledger.buy("005930", 30, BigDecimal("1100"), t)

        val pos = ledger.snapshot(t).positions.single()
        pos.quantity shouldBe 80L
        // (1000*50 + 1100*30)/80 = 1037.5
        pos.averagePrice.compareTo(BigDecimal("1037.500000")) shouldBe 0
        pos.filledLots shouldBe 2
        pos.executedExitStages shouldBe emptySet()
    }

    @Test
    fun `partial sell preserves average price filled lots and recorded exit stage`() {
        val ledger = ScenarioLedger(initialCash = BigDecimal("10000000"), initialPositions = emptyList())
        ledger.buy("005930", 100, BigDecimal("1000"), t)

        // 분할 익절 단계0 — 보유 50 매도(부분 청산)
        ledger.sell("005930", 50, BigDecimal("1100"), t)
        ledger.recordExitStage("005930", 0)

        val pos = ledger.snapshot(t).positions.single()
        pos.quantity shouldBe 50L
        pos.averagePrice.compareTo(BigDecimal("1000")) shouldBe 0 // 부분 청산은 평단 보존
        pos.filledLots shouldBe 1
        pos.executedExitStages shouldBe setOf(0)
    }

    @Test
    fun `full exit removes position and resets lot state`() {
        val ledger = ScenarioLedger(initialCash = BigDecimal("10000000"), initialPositions = emptyList())
        ledger.buy("005930", 100, BigDecimal("1000"), t)
        ledger.recordExitStage("005930", 0)

        // 전량 청산
        ledger.sell("005930", 100, BigDecimal("1200"), t)

        ledger.snapshot(t).positions shouldBe emptyList()
        ledger.availableQuantity("005930") shouldBe 0L
    }

    @Test
    fun `record exit stage is idempotent and no-op when not held`() {
        val ledger = ScenarioLedger(initialCash = BigDecimal("10000000"), initialPositions = emptyList())
        ledger.buy("005930", 10, BigDecimal("1000"), t)
        ledger.recordExitStage("005930", 0)
        ledger.recordExitStage("005930", 0) // 멱등
        ledger.recordExitStage("000660", 1) // 미보유 — no-op

        ledger
            .snapshot(t)
            .positions
            .single()
            .executedExitStages shouldBe setOf(0)
    }

    @Test
    fun `single entry without scale-in converges filled lots to one (zero regression)`() {
        val ledger = ScenarioLedger(initialCash = BigDecimal("10000000"), initialPositions = emptyList())
        ledger.buy("005930", 1, BigDecimal("71000"), t)

        val pos = ledger.snapshot(t).positions.single()
        pos.quantity shouldBe 1L
        pos.averagePrice.compareTo(BigDecimal("71000")) shouldBe 0
        pos.filledLots shouldBe 1
        pos.executedExitStages shouldBe emptySet()
    }

    @Test
    fun `seed position is treated as one filled lot`() {
        val ledger =
            ScenarioLedger(
                initialCash = BigDecimal("10000000"),
                initialPositions = listOf(ScenarioPositionSeed("005930", 10, BigDecimal("1000"))),
            )
        val pos = ledger.snapshot(t).positions.single()
        pos.filledLots shouldBe 1
    }
}
