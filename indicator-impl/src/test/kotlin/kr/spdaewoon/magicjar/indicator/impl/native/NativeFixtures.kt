package kr.spdaewoon.magicjar.indicator.impl.native

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days

/**
 * Native 5종 정확성 fixture — KIS indicators.py 동일 input과 1:1 cross-check.
 *
 * 본 fixture는 결정적 input (sine + drift) 30 캔들로 검증 가능 범위 보장.
 */
internal object NativeFixtures {
    fun makeCandles(closes: List<Double>): List<Candle> {
        val baseTs = Instant.parse("2024-01-01T00:00:00Z")
        return closes.mapIndexed { i, c ->
            Candle(
                id = null,
                code = "TEST",
                interval = CandleInterval.ONE_DAY,
                ts = baseTs.plus(i.days),
                openRaw = BigDecimal.valueOf(c - 1.0),
                highRaw = BigDecimal.valueOf(c + 2.0),
                lowRaw = BigDecimal.valueOf(c - 2.0),
                closeRaw = BigDecimal.valueOf(c),
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 1000L,
                source = "TEST",
            )
        }
    }

    /**
     * 30일 결정적 시계열 — 100, 102, 101, 103, 105, ... 등.
     * KIS Python `pd.Series([100, 102, 101, ...]).rolling(20).mean()` 결과와 정합 검증 가능.
     */
    val DETERMINISTIC_CLOSES: List<Double> =
        listOf(
            100.0,
            102.0,
            101.0,
            103.0,
            105.0,
            104.0,
            106.0,
            108.0,
            107.0,
            109.0,
            111.0,
            110.0,
            112.0,
            114.0,
            113.0,
            115.0,
            117.0,
            116.0,
            118.0,
            120.0,
            119.0,
            121.0,
            123.0,
            122.0,
            124.0,
            126.0,
            125.0,
            127.0,
            129.0,
            128.0,
        )
}
