package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * DslIdentifierMapper LOCK 매핑 검증 (spec §5-5 LOCK).
 *
 * spec §1-2 DoD #16 정합 — DSL alias → IndicatorSpec 정합 검증.
 */
class DslIdentifierMapperTest {
    private val mapper = DslIdentifierMapper()

    @Test
    fun `simple ma — 1 to 1 mapping`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("ma", listOf(5)))
        assertEquals("ma", spec.name)
        assertEquals(5, spec.params["period"])
    }

    @Test
    fun `bb_upper — alias to bb with std`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("bb_upper", listOf(20, 2)))
        assertEquals("bb", spec.name)
        assertEquals(20, spec.params["period"])
        // std 값은 BigDecimal — value compare (scale 무관)
        val std = spec.params["std"] as BigDecimal
        assertEquals(0, BigDecimal("2").compareTo(std))
    }

    @Test
    fun `bb_middle — alias to bb without std`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("bb_middle", listOf(20)))
        assertEquals("bb", spec.name)
        assertEquals(20, spec.params["period"])
    }

    @Test
    fun `macd_signal — alias to macd with fast slow signal`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("macd_signal", listOf(12, 26, 9)))
        assertEquals("macd", spec.name)
        assertEquals(12, spec.params["fast"])
        assertEquals(26, spec.params["slow"])
        assertEquals(9, spec.params["signal"])
    }

    @Test
    fun `stoch_k — alias to stochastic_k`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("stoch_k", listOf(14)))
        assertEquals("stochastic_k", spec.name)
        assertEquals(14, spec.params["period"])
    }

    @Test
    fun `stoch_d — alias to stochastic_d`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("stoch_d", listOf(14)))
        assertEquals("stochastic_d", spec.name)
    }

    @Test
    fun `fvg_bullish_mid — alias to fvg with mitigation pct`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("fvg_bullish_mid", listOf(50)))
        assertEquals("fvg", spec.name)
        assertEquals(0, BigDecimal("50").compareTo(spec.params["mitigationPct"] as BigDecimal))
    }

    @Test
    fun `ob_bullish_active — alias to order_block params`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("ob_bullish_active", listOf(20, 30, 50)))
        assertEquals("order_block", spec.name)
        assertEquals(20, spec.params["swingLookback"])
        assertEquals(30, spec.params["searchLookback"])
        assertEquals(0, BigDecimal("50").compareTo(spec.params["mitigationPct"] as BigDecimal))
    }

    @Test
    fun `order_block defaults — detection bos and range wick when positions 3 4 absent`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("order_block", listOf(20, 20, 50)))
        assertEquals("order_block", spec.name)
        assertEquals("bos", spec.params["detection"])
        assertEquals("wick", spec.params["range"])
    }

    @Test
    fun `order_block — detection engulfing and range body from positions 3 4`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("order_block", listOf(20, 20, 100, "engulfing", "body")))
        assertEquals("order_block", spec.name)
        assertEquals(20, spec.params["swingLookback"])
        assertEquals(20, spec.params["searchLookback"])
        assertEquals(0, BigDecimal("100").compareTo(spec.params["mitigationPct"] as BigDecimal))
        assertEquals("engulfing", spec.params["detection"])
        assertEquals("body", spec.params["range"])
    }

    @Test
    fun `order_block — invalid detection range fall back to defaults`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("order_block", listOf(20, 20, 50, "nonsense", "garbage")))
        assertEquals("bos", spec.params["detection"])
        assertEquals("wick", spec.params["range"])
    }

    @Test
    fun `order_block — detection engulfing case-insensitive`() {
        val spec = mapper.toIndicatorSpec(IndicatorRef("order_block", listOf(20, 20, 50, "ENGULFING", "BODY")))
        assertEquals("engulfing", spec.params["detection"])
        assertEquals("body", spec.params["range"])
    }

    @Test
    fun `unknown identifier — UnknownDslSymbolException`() {
        assertThrows(StrategyException.UnknownDslSymbolException::class.java) {
            mapper.toIndicatorSpec(IndicatorRef("definitely_not_an_indicator", emptyList()))
        }
    }

    @Test
    fun `extractValue — multi-output bb_upper`() {
        val ref = IndicatorRef("bb_upper", listOf(20, 2))
        val series =
            listOf(
                IndicatorPoint(
                    time = Instant.fromEpochSeconds(1_700_000_000L),
                    values =
                        mapOf(
                            "upper" to BigDecimal("105"),
                            "middle" to BigDecimal("100"),
                            "lower" to BigDecimal("95"),
                        ),
                ),
            )
        val result = IndicatorResult(spec = IndicatorSpec("bb", emptyMap()), series = series, source = "test")
        val value = mapper.extractValue(result, ref)
        assertEquals(BigDecimal("105"), value)
    }

    @Test
    fun `extractValue — single output ma`() {
        val ref = IndicatorRef("ma", listOf(20))
        val series =
            listOf(
                IndicatorPoint(
                    time = Instant.fromEpochSeconds(1_700_000_000L),
                    values = mapOf("ma" to BigDecimal("100")),
                ),
            )
        val result = IndicatorResult(spec = IndicatorSpec("ma", mapOf("period" to 20)), series = series, source = "test")
        assertEquals(BigDecimal("100"), mapper.extractValue(result, ref))
    }

    @Test
    fun `macd_signal extracts signal output`() {
        val ref = IndicatorRef("macd_signal", listOf(12, 26, 9))
        val series =
            listOf(
                IndicatorPoint(
                    time = Instant.fromEpochSeconds(1_700_000_000L),
                    values =
                        mapOf(
                            "macd" to BigDecimal("1.5"),
                            "signal" to BigDecimal("0.8"),
                            "histogram" to BigDecimal("0.7"),
                        ),
                ),
            )
        val result =
            IndicatorResult(
                spec = IndicatorSpec("macd", mapOf("fast" to 12, "slow" to 26, "signal" to 9)),
                series = series,
                source = "test",
            )
        assertEquals(BigDecimal("0.8"), mapper.extractValue(result, ref))
    }
}
