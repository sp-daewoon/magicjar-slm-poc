package kr.spdaewoon.magicjar.indicator.impl.smartmoney

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

class SmartMoneyIndicatorsTest {
    @Test
    fun `fvg detects bullish gap and exposes active zone`() {
        val candles =
            listOf(
                candle(0, 100, 105, 98, 102),
                candle(1, 102, 108, 101, 107),
                candle(2, 111, 115, 110, 114),
            )

        val result = SmartMoneyIndicators.fvg(IndicatorSpec("fvg", emptyMap()), candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        last["bullish_top"] shouldBe BigDecimal(110)
        last["bullish_bottom"] shouldBe BigDecimal(105)
        last["bullish_mid"] shouldBe BigDecimal("107.50000000")
    }

    @Test
    fun `fvg mitigation removes bullish zone`() {
        val candles =
            listOf(
                candle(0, 100, 105, 98, 102),
                candle(1, 102, 108, 101, 107),
                candle(2, 111, 115, 110, 114),
                candle(3, 109, 111, 107, 108),
            )

        val result = SmartMoneyIndicators.fvg(IndicatorSpec("fvg", mapOf("mitigationPct" to 50)), candles)
        result.series.last().values["bullish_active"] shouldBe BigDecimal.ZERO
    }

    @Test
    fun `order block detects bullish bos from last bearish candle`() {
        val candles =
            listOf(
                candle(0, 100, 101, 97, 98),
                candle(1, 98, 100, 96, 99),
                candle(2, 99, 102, 98, 101),
                candle(3, 101, 103, 100, 102),
                candle(4, 102, 110, 101, 109),
            )

        val spec = IndicatorSpec("order_block", mapOf("swingLookback" to 3, "searchLookback" to 4, "mitigationPct" to 50))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        last["bos_direction"] shouldBe BigDecimal.ONE
        last["bullish_top"] shouldBe BigDecimal(101)
        last["bullish_bottom"] shouldBe BigDecimal(97)
    }

    @Test
    fun `order block engulfing detects bullish ob from engulfed bearish candle`() {
        // prev(idx0) 음봉: open 105 close 100. curr(idx1) 양봉이 prev 몸통 완전 장악: open 99 close 106
        val candles =
            listOf(
                candle(0, 105, 106, 99, 100),
                candle(1, 99, 107, 98, 106),
            )

        val spec = IndicatorSpec("order_block", mapOf("detection" to "engulfing", "range" to "wick"))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        last["bos_direction"] shouldBe BigDecimal.ONE
        // wick range — 기준봉(prev idx0) 고가/저가
        last["bullish_top"] shouldBe BigDecimal(106)
        last["bullish_bottom"] shouldBe BigDecimal(99)
    }

    @Test
    fun `order block engulfing detects bearish ob from engulfed bullish candle`() {
        // prev(idx0) 양봉: open 100 close 105. curr(idx1) 음봉이 prev 몸통 완전 장악: open 106 close 99
        val candles =
            listOf(
                candle(0, 100, 106, 99, 105),
                candle(1, 106, 107, 98, 99),
            )

        val spec = IndicatorSpec("order_block", mapOf("detection" to "engulfing"))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bearish_active"] shouldBe BigDecimal.ONE
        last["bos_direction"] shouldBe BigDecimal("-1")
        // range default = wick — 기준봉(prev idx0) 고가/저가
        last["bearish_top"] shouldBe BigDecimal(106)
        last["bearish_bottom"] shouldBe BigDecimal(99)
    }

    @Test
    fun `order block engulfing with body range uses engulfed candle body`() {
        // prev(idx0) 음봉 몸통: open 105 close 100 → body top=105 bottom=100
        val candles =
            listOf(
                candle(0, 105, 110, 95, 100),
                candle(1, 99, 112, 98, 106),
            )

        val spec = IndicatorSpec("order_block", mapOf("detection" to "engulfing", "range" to "body"))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        // body range — prev 몸통 max/min(open,close)
        last["bullish_top"] shouldBe BigDecimal(105)
        last["bullish_bottom"] shouldBe BigDecimal(100)
    }

    @Test
    fun `order block engulfing does not detect when prev body not fully covered`() {
        // prev(idx0) 음봉: open 105 close 100. curr 양봉이 prev 몸통을 완전히 감싸지 못함 (close 103 < prev.open 105)
        val candles =
            listOf(
                candle(0, 105, 106, 99, 100),
                candle(1, 101, 104, 100, 103),
            )

        val spec = IndicatorSpec("order_block", mapOf("detection" to "engulfing", "range" to "body"))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ZERO
        last["bearish_active"] shouldBe BigDecimal.ZERO
        last["bos_direction"] shouldBe BigDecimal.ZERO
    }

    @Test
    fun `order block engulfing zone removed when close exits with full mitigation`() {
        // bullish OB 형성 후, 이후 캔들 종가가 zone bottom 아래로 이탈 → invalidation 소멸
        val candles =
            listOf(
                candle(0, 105, 106, 99, 100),
                candle(1, 99, 107, 98, 106),
                candle(2, 100, 101, 90, 92),
            )

        val spec = IndicatorSpec("order_block", mapOf("detection" to "engulfing", "range" to "body", "mitigationPct" to 100))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        result.series.last().values["bullish_active"] shouldBe BigDecimal.ZERO
    }

    @Test
    fun `order block bos default with body range narrows zone to ob body`() {
        // 기존 bullish bos 시나리오에 range=body 적용 — 기준봉 idx2 (open 99 close 101 음봉 아님? )
        // 기존 테스트 OB는 idx2: open 99 close 101 (양봉) → 반대색(음봉) 탐색이므로 idx 다름.
        // bos 탐색은 close<open 인 마지막 음봉. idx1: open 98 close 99 (양봉), idx0: open 100 close 98 (음봉)
        // → OB = idx0 (음봉). body top=max(100,98)=100 bottom=min=98
        val candles =
            listOf(
                candle(0, 100, 101, 97, 98),
                candle(1, 98, 100, 96, 99),
                candle(2, 99, 102, 98, 101),
                candle(3, 101, 103, 100, 102),
                candle(4, 102, 110, 101, 109),
            )

        val spec =
            IndicatorSpec(
                "order_block",
                mapOf("swingLookback" to 3, "searchLookback" to 4, "mitigationPct" to 50, "detection" to "bos", "range" to "body"),
            )
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        last["bos_direction"] shouldBe BigDecimal.ONE
        // body range — 기준봉 idx0 몸통 (open 100 / close 98) → top 100 bottom 98 (기존 wick는 101/97)
        last["bullish_top"] shouldBe BigDecimal(100)
        last["bullish_bottom"] shouldBe BigDecimal(98)
    }

    private fun candle(
        minute: Int,
        open: Long,
        high: Long,
        low: Long,
        close: Long,
    ): Candle =
        Candle(
            id = null,
            code = "TEST",
            interval = CandleInterval.ONE_MIN,
            ts = Instant.parse("2026-05-30T00:00:00Z").plus(minute.minutes),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "TEST",
        )
}
