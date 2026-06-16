package kr.spdaewoon.magicjar.api.ai.explain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.toJavaInstant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.indicator.impl.NativeIndicatorSource
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

class AiChartPromptContextEnricherTest {
    @Test
    fun `CHART context를 원문 candle 배열 없이 실측 요약으로 보강한다`() {
        val candles = sampleCandles(count = 80)
        val enricher =
            AiChartPromptContextEnricher(
                candleRepository = StubCandleRepository(candles),
                indicatorEngine = IndicatorEngine(listOf(NativeIndicatorSource())),
            )

        val result =
            enricher.enrich(
                mapOf(
                    "code" to "005930",
                    "timeframe" to "1d",
                    "currentPrice" to "1400",
                ),
            )

        result["timeframe"] shouldBe "1d(일봉)"
        result["chartDataSource"].toString() shouldContain "mock/synthetic=0"
        result["indicators"].toString() shouldContain "RSI14"
        result["indicators"].toString() shouldContain "MA20"
        result["priceFlow"].toString() shouldContain "supportCandidate="
        result["priceFlow"].toString() shouldContain "resistanceCandidate="
    }

    @Test
    fun `미지원 timeframe은 데이터 없음 context로 보강한다`() {
        val enricher =
            AiChartPromptContextEnricher(
                candleRepository = StubCandleRepository(emptyList()),
                indicatorEngine = IndicatorEngine(listOf(NativeIndicatorSource())),
            )

        val result = enricher.enrich(mapOf("code" to "005930", "timeframe" to "2h"))

        result["indicators"].toString() shouldContain "데이터 없음"
        result["priceFlow"].toString() shouldContain "지원하지 않는 timeframe"
    }

    private fun sampleCandles(count: Int): List<Candle> {
        val baseEpoch = Instant.parse("2026-01-01T00:00:00Z").epochSecond
        return (0 until count).map { index ->
            val close = BigDecimal.valueOf(1000L + index * 5L)
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = KtInstant.fromEpochSeconds(baseEpoch + index * 86_400L),
                openRaw = close.subtract(BigDecimal("3")),
                highRaw = close.add(BigDecimal("10")),
                lowRaw = close.subtract(BigDecimal("12")),
                closeRaw = close,
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 10_000L + index * 100L,
                source = "KIS",
            )
        }
    }

    private class StubCandleRepository(
        private val candles: List<Candle>,
    ) : CandleRepository {
        override fun saveAll(candles: List<Candle>): Int = 0

        override fun upsertAll(candles: List<Candle>): Int = 0

        override fun countByCodeAndInterval(
            code: String,
            interval: CandleInterval,
        ): Long = candles.count { it.code == code && it.interval == interval }.toLong()

        override fun countByCodeAndIntervalIn(
            code: String,
            intervals: List<CandleInterval>,
        ): Long = candles.count { it.code == code && it.interval in intervals }.toLong()

        override fun findByCodeAndInterval(
            code: String,
            interval: CandleInterval,
        ): List<Candle> = candles.filter { it.code == code && it.interval == interval }

        override fun findLatestByCodeAndInterval(
            code: String,
            interval: CandleInterval,
            limit: Int,
        ): List<Candle> = findByCodeAndInterval(code, interval).takeLast(limit)

        override fun findMaxTsByCodeAndInterval(
            code: String,
            interval: CandleInterval,
        ): Instant? = findByCodeAndInterval(code, interval).lastOrNull()?.ts?.toJavaInstant()

        override fun findAllTsByCodeAndIntervalBetween(
            code: String,
            interval: CandleInterval,
            from: Instant,
            to: Instant,
        ): List<Instant> =
            findByCodeAndInterval(code, interval)
                .map { it.ts.toJavaInstant() }
                .filter { !it.isBefore(from) && !it.isAfter(to) }
    }
}
