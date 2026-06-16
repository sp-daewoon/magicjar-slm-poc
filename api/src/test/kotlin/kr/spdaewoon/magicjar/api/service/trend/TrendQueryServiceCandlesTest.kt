package kr.spdaewoon.magicjar.api.service.trend

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshotReader
import kr.spdaewoon.magicjar.market.calculator.TrendCalculator
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlinx.datetime.Instant as KxInstant

class TrendQueryServiceCandlesTest {
    private val candleRepository: CandleRepository = mockk(relaxed = true)
    private val trendCalculator: TrendCalculator = mockk(relaxed = true)
    private val trendPreferenceService: TrendPreferenceService = mockk(relaxed = true)
    private val marketIndexSnapshotReader: MarketIndexSnapshotReader = mockk()
    private val service =
        TrendQueryService(
            candleRepository = candleRepository,
            trendCalculator = trendCalculator,
            trendPreferenceService = trendPreferenceService,
            marketIndexSnapshotReader = marketIndexSnapshotReader,
        )

    @Test
    fun `candles 1m - 실제 snapshot series를 인접 가격 OHLC로 변환한다`() {
        every { marketIndexSnapshotReader.findIndexSnapshotSeries("0001", 3) } returns
            listOf(
                snapshot("2026-05-26T00:00:00Z", "100.00"),
                snapshot("2026-05-26T00:01:00Z", "103.00"),
                snapshot("2026-05-26T00:02:00Z", "99.00"),
            )

        val candles = service.candles(code = "0001", interval = "1m", limit = 3)

        candles.size shouldBe 3
        candles[0].open shouldBe BigDecimal("100.00")
        candles[0].high shouldBe BigDecimal("100.00")
        candles[0].low shouldBe BigDecimal("100.00")
        candles[0].close shouldBe BigDecimal("100.00")
        candles[1].open shouldBe BigDecimal("100.00")
        candles[1].high shouldBe BigDecimal("103.00")
        candles[1].low shouldBe BigDecimal("100.00")
        candles[1].close shouldBe BigDecimal("103.00")
        candles[2].open shouldBe BigDecimal("103.00")
        candles[2].high shouldBe BigDecimal("103.00")
        candles[2].low shouldBe BigDecimal("99.00")
        candles[2].close shouldBe BigDecimal("99.00")
        verify(exactly = 1) { marketIndexSnapshotReader.findIndexSnapshotSeries("0001", 3) }
    }

    @Test
    fun `candles 1d - KST 일별 series를 사용하고 limit은 최대 1000으로 clamp한다`() {
        every { marketIndexSnapshotReader.findDailyIndexSeries("0001", 1000) } returns emptyList()

        val candles = service.candles(code = "0001", interval = "1d", limit = 9999)

        candles shouldBe emptyList()
        verify(exactly = 1) { marketIndexSnapshotReader.findDailyIndexSeries("0001", 1000) }
    }

    private fun snapshot(
        ts: String,
        price: String,
    ): MarketIndexSnapshot =
        MarketIndexSnapshot(
            code = "0001",
            market = "KRX",
            ts = KxInstant.parse(ts),
            price = BigDecimal(price),
            changeRate = null,
        )
}
