package kr.spdaewoon.magicjar.domain.scenario

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ScenarioReplayEngineTest {
    @Test
    fun `empty candles abort as data not ready without creating a virtual order`() {
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) })

        val result = engine.run(baseRequest(), candles = emptyList())

        (result is ScenarioReplayResult.Aborted) shouldBe true
        result.events.map { it.type } shouldBe listOf(ScenarioEventType.DATA_NOT_READY)
        result.events.first().message shouldContain "가상 주문을 만들지 않았습니다"
    }

    @Test
    fun `balance lookup rate limit holds replay sell order and does not look like real sell failure`() {
        val faultTime = t("2026-06-02T07:15:00Z")
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.sell(quantity = 1) })
        val request =
            baseRequest(
                code = "000660",
                initialPositions = listOf(ScenarioPositionSeed("000660", 1, bd("150000"))),
                faults = listOf(ScenarioFault(faultTime, ScenarioFaultType.BALANCE_LOOKUP_RATE_LIMITED)),
            )

        val result = engine.run(request, candles = candles(code = "000660"))

        val success = result as ScenarioReplayResult.Success
        success.events.map { it.type } shouldContain ScenarioEventType.FAULT_INJECTED
        success.events.map { it.type } shouldContain ScenarioEventType.ORDER_HELD
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_FILLED
        val held = success.events.first { it.type == ScenarioEventType.ORDER_HELD }
        held.message shouldContain "rate limit"
        held.message shouldContain "리플레이 가상 주문을 보류"
        held.message shouldNotContain "실제"
        held.message shouldNotContain "주문 실패"
    }

    @Test
    fun `sell without replay position is risk blocked before virtual order request`() {
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.sell(quantity = 1) })

        val result = engine.run(baseRequest(code = "000660"), candles = candles(code = "000660"))

        val success = result as ScenarioReplayResult.Success
        success.events.map { it.type } shouldContain ScenarioEventType.RISK_BLOCKED
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_REQUESTED
        val blocked = success.events.first { it.type == ScenarioEventType.RISK_BLOCKED }
        blocked.message shouldContain "리플레이 포지션 기준"
        blocked.message shouldContain "가상 SELL 주문을 만들지 않았습니다"
    }

    @Test
    fun `buy signal fills at next candle open and updates replay position only`() {
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) })

        val result = engine.run(baseRequest(initialCash = bd("1000000")), candles = candles())

        val success = result as ScenarioReplayResult.Success
        success.events.map { it.type } shouldContain ScenarioEventType.ORDER_REQUESTED
        success.events.map { it.type } shouldContain ScenarioEventType.ORDER_FILLED
        success.events.map { it.type } shouldContain ScenarioEventType.POSITION_UPDATED
        val position = success.ledger.positions.single()
        position.quantity shouldBe 1
        position.averagePrice shouldBe bd("71000")
    }

    @Test
    fun `buy with insufficient replay cash is risk blocked instead of throwing`() {
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) })

        val result = engine.run(baseRequest(initialCash = bd("1000")), candles = candles())

        val success = result as ScenarioReplayResult.Success
        success.events.map { it.type } shouldContain ScenarioEventType.RISK_BLOCKED
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_FILLED
        val blocked = success.events.first { it.title == "리플레이 현금 부족" }
        blocked.message shouldContain "가상 BUY 주문을 체결하지 않았습니다"
    }

    @Test
    fun `repeated buy after insufficient replay cash is folded until the signal clears`() {
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) })
        val request =
            baseRequest(
                initialCash = bd("1000"),
                to = t("2026-06-02T07:18:00Z"),
            )

        val result =
            engine.run(
                request,
                candles =
                    listOf(
                        candle(code = "005930", openedAt = "2026-06-02T07:14:00Z", closedAt = "2026-06-02T07:15:00Z", open = "70000"),
                        candle(code = "005930", openedAt = "2026-06-02T07:15:00Z", closedAt = "2026-06-02T07:16:00Z", open = "71000"),
                        candle(code = "005930", openedAt = "2026-06-02T07:16:00Z", closedAt = "2026-06-02T07:17:00Z", open = "72000"),
                        candle(code = "005930", openedAt = "2026-06-02T07:17:00Z", closedAt = "2026-06-02T07:18:00Z", open = "73000"),
                    ),
            )

        val success = result as ScenarioReplayResult.Success
        success.events.count { it.type == ScenarioEventType.SIGNAL_BUY } shouldBe 1
        success.events.count { it.type == ScenarioEventType.ORDER_REQUESTED } shouldBe 1
        success.events.count { it.title == "리플레이 현금 부족" } shouldBe 1
        success.events.count { it.title == "리플레이 반복 BUY 접힘" } shouldBe 3
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_FILLED
    }

    @Test
    fun `market closed fault is shown as replay order rejected`() {
        val faultTime = t("2026-06-02T07:15:00Z")
        val engine = ScenarioReplayEngine(strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) })
        val request = baseRequest(faults = listOf(ScenarioFault(faultTime, ScenarioFaultType.MARKET_CLOSED)))

        val result = engine.run(request, candles = candles())

        val success = result as ScenarioReplayResult.Success
        success.events.map { it.type } shouldContain ScenarioEventType.ORDER_REJECTED
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_FILLED
        val rejected = success.events.first { it.type == ScenarioEventType.ORDER_REJECTED }
        rejected.title shouldBe "가상 주문 거절"
        rejected.message shouldContain "리플레이 시장 세션 규칙상"
    }

    @Test
    fun `out of session boundary is gate skipped and not evaluated (live parity)`() {
        // closedAt 07:15Z = 16:15 KST (정규장 마감 이후) → 라이브와 동일하게 평가 skip.
        val engine =
            ScenarioReplayEngine(
                strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) },
                marketHoursGate = ScenarioMarketHoursGate { false },
            )

        val result = engine.run(baseRequest(initialCash = bd("1000000")), candles = candles())

        val success = result as ScenarioReplayResult.Success
        success.events.map { it.type } shouldContain ScenarioEventType.GATE_SKIPPED
        success.events.map { it.type } shouldNotContain ScenarioEventType.SIGNAL_BUY
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_REQUESTED
        success.events.map { it.type } shouldNotContain ScenarioEventType.ORDER_FILLED
        val skipped = success.events.first { it.type == ScenarioEventType.GATE_SKIPPED }
        skipped.message shouldContain "정규장"
        skipped.payload["reasonCode"] shouldBe "GATE_SKIPPED:MARKET_HOURS"
    }

    @Test
    fun `not simulated gates are honestly recorded at run start instead of silently passing`() {
        val engine =
            ScenarioReplayEngine(
                strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.hold() },
                notSimulatedGates =
                    listOf(
                        ScenarioNotSimulatedGate("KILL_SWITCH_ON"),
                        ScenarioNotSimulatedGate("DAILY_LOSS_LIMIT"),
                    ),
            )

        val result = engine.run(baseRequest(), candles = candles())

        val success = result as ScenarioReplayResult.Success
        val notSimulated = success.events.filter { it.type == ScenarioEventType.GATE_NOT_SIMULATED }
        notSimulated.map { it.payload["gate"] } shouldBe listOf("KILL_SWITCH_ON", "DAILY_LOSS_LIMIT")
        notSimulated.first().payload["reasonCode"] shouldBe "GATE_NOT_SIMULATED:KILL_SWITCH_ON"
    }

    @Test
    fun `gate all-pass replay is identical to legacy default engine (zero regression)`() {
        val request = baseRequest(initialCash = bd("1000000"))
        val legacy =
            ScenarioReplayEngine(
                strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) },
            ).run(request, candles = candles())
        val gated =
            ScenarioReplayEngine(
                strategyEvaluator = ScenarioStrategyEvaluator { ScenarioSignalDecision.buy(quantity = 1) },
                marketHoursGate = ScenarioMarketHoursGate { true },
            ).run(request, candles = candles())

        val legacyTypes = (legacy as ScenarioReplayResult.Success).events.map { it.type }
        val gatedTypes = (gated as ScenarioReplayResult.Success).events.map { it.type }
        gatedTypes shouldBe legacyTypes
        gatedTypes shouldNotContain ScenarioEventType.GATE_SKIPPED
        gated.ledger.positions shouldBe legacy.ledger.positions
    }

    private fun baseRequest(
        code: String = "005930",
        initialCash: BigDecimal = bd("1000000"),
        initialPositions: List<ScenarioPositionSeed> = emptyList(),
        faults: List<ScenarioFault> = emptyList(),
        to: Instant = t("2026-06-02T07:17:00Z"),
    ): ScenarioReplayRequest =
        ScenarioReplayRequest(
            userId = "7468",
            strategyId = 43L,
            strategyName = "리플레이 QA 전략",
            code = code,
            interval = "1m",
            from = t("2026-06-02T07:15:00Z"),
            to = to,
            initialCash = initialCash,
            initialPositions = initialPositions,
            faults = faults,
        )

    private fun candles(code: String = "005930"): List<ScenarioCandle> =
        listOf(
            candle(code = code, openedAt = "2026-06-02T07:14:00Z", closedAt = "2026-06-02T07:15:00Z", open = "70000"),
            candle(code = code, openedAt = "2026-06-02T07:15:00Z", closedAt = "2026-06-02T07:16:00Z", open = "71000"),
        )

    private fun candle(
        code: String,
        openedAt: String,
        closedAt: String,
        open: String,
    ): ScenarioCandle =
        ScenarioCandle(
            code = code,
            interval = "1m",
            openedAt = t(openedAt),
            closedAt = t(closedAt),
            open = bd(open),
            high = bd(open).add(bd("500")),
            low = bd(open).subtract(bd("500")),
            close = bd(open).add(bd("100")),
            volume = 1000,
        )

    companion object {
        private fun t(value: String): Instant = Instant.parse(value)

        private fun bd(value: String): BigDecimal = BigDecimal(value)
    }
}
