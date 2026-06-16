package kr.spdaewoon.magicjar.domain.strategyresearch

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * StrategyResearchEngine 도메인 로직 테스트.
 *
 * research-candidates-db 전환 후 진입 시그널은 [CandidateEntryEvaluator] 포트로 위임된다.
 * 도메인 단독 테스트에서는 결정론적 stub evaluator를 주입해 엔진의 시뮬레이션·통계·exit profile 스윕을
 * DSL 평가기와 분리해 검증한다(DSL 실제 평가 정합은 api 모듈 통합 테스트가 담당).
 */
class StrategyResearchEngineTest {
    /**
     * VOLUME_WASHOUT_REBOUND 후보가 index 26(직전 투매 음봉 직후 양봉)에서 진입하도록 하는 stub.
     * 기존 Kotlin 람다의 핵심 의미(투매 후 양봉 회복)를 fixture 한정으로 재현한다.
     */
    private val washoutStub =
        CandidateEntryEvaluator { entryDsl, candles, index ->
            entryDsl.contains("close > open") &&
                index == 26 &&
                candles[index].close > candles[index].open
        }

    @Test
    fun `washout candidate uses next candle open and reports a take profit trade`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)
        val report =
            engine.research(
                request = request(targetProfitPct = 1.0, stopLossPct = 0.8, maxHoldBars = 4),
                candles = washoutCandles(),
            )

        val candidate = report.candidates.first { it.key == "VOLUME_WASHOUT_REBOUND" }
        candidate.trades.shouldNotBeEmpty()
        val trade = candidate.trades.first()
        trade.signalTime shouldBe t("2026-04-28T00:26:00Z")
        trade.entryTime shouldBe t("2026-04-28T00:27:00Z")
        trade.exitReason shouldBe StrategyResearchExitReason.TAKE_PROFIT
        trade.returnPct.shouldBeGreaterThan(0.0)
        candidate.stats.totalReturnPct.shouldBeGreaterThan(0.0)
        candidate.riskRules.joinToString(" ") shouldContain "다음 candle 시가"
    }

    @Test
    fun `insufficient candles returns warnings without candidates`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val report = engine.research(request(), washoutCandles().take(10))

        report.candidates shouldBe emptyList()
        report.warnings.joinToString(" ") shouldContain "최소"
    }

    @Test
    fun `research evaluates the full builtin candidate pool from the provider`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val report = engine.research(request(), washoutCandles(80))

        report.candidates.size shouldBe 18
        report.candidates.map { it.key }.toSet() shouldBe
            BuiltinResearchCandidates.DEFINITIONS.map { it.key }.toSet()
    }

    @Test
    fun `entryDsl is exposed as the authoritative draft on every candidate`() {
        val engine = StrategyResearchEngine(entryEvaluator = washoutStub)

        val report = engine.research(request(), washoutCandles(80))

        val byKey = BuiltinResearchCandidates.DEFINITIONS.associateBy { it.key }
        report.candidates.forEach { candidate ->
            candidate.entryDslDraft shouldBe byKey.getValue(candidate.key).entryDsl
        }
    }

    private fun request(
        targetProfitPct: Double = 1.5,
        stopLossPct: Double = 0.8,
        maxHoldBars: Int = 12,
    ): StrategyResearchRequest =
        StrategyResearchRequest(
            userId = "74683223",
            code = "038110",
            interval = "1m",
            from = t("2026-04-28T00:00:00Z"),
            to = t("2026-04-28T00:44:00Z"),
            targetProfitPct = targetProfitPct,
            stopLossPct = stopLossPct,
            maxHoldBars = maxHoldBars,
        )

    private fun washoutCandles(size: Int = 45): List<ResearchCandle> {
        val candles = mutableListOf<ResearchCandle>()
        val baseTs = t("2026-04-28T00:00:00Z")
        repeat(size) { index ->
            val ts = Instant.fromEpochSeconds(baseTs.epochSeconds + index * 60L)
            val open: String
            val high: String
            val low: String
            val close: String
            val volume: Long
            when (index) {
                25 -> {
                    open = "100.0"
                    high = "100.2"
                    low = "97.8"
                    close = "98.0"
                    volume = 3000
                }
                26 -> {
                    open = "98.0"
                    high = "99.3"
                    low = "97.9"
                    close = "99.0"
                    volume = 1800
                }
                27 -> {
                    open = "99.0"
                    high = "100.5"
                    low = "98.8"
                    close = "100.1"
                    volume = 1600
                }
                else -> {
                    open = "100.0"
                    high = "100.4"
                    low = "99.6"
                    close = "100.0"
                    volume = 1000
                }
            }
            candles += candle(ts, open, high, low, close, volume)
        }
        return candles
    }

    private fun candle(
        ts: Instant,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: Long,
    ): ResearchCandle =
        ResearchCandle(
            code = "038110",
            interval = "1m",
            ts = ts,
            open = bd(open),
            high = bd(high),
            low = bd(low),
            close = bd(close),
            volume = volume,
        )

    private fun t(value: String): Instant = Instant.parse(value)

    private fun bd(value: String): BigDecimal = BigDecimal(value)
}
