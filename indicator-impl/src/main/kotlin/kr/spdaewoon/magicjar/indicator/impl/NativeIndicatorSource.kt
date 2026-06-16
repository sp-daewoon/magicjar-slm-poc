package kr.spdaewoon.magicjar.indicator.impl

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.native.NativeBollingerBands
import kr.spdaewoon.magicjar.indicator.impl.native.NativeEma
import kr.spdaewoon.magicjar.indicator.impl.native.NativeMa
import kr.spdaewoon.magicjar.indicator.impl.native.NativeMacd
import kr.spdaewoon.magicjar.indicator.impl.native.NativeRsi
import org.springframework.stereotype.Component

/**
 * magicJar 자체 5종 indicator (ADR 0021 §2-3).
 *
 * priority=50 — KIS(100)보다 우선 사용. cross-check 검증 후 신뢰성 확보.
 * 5종: ma · ema · rsi · macd · bb (사용자 차트 overlay·DSL 프리셋 사용 빈도 최상위).
 *
 * 알고리즘은 KIS indicators.py 와 1:1 정합 (drift < 0.001% 보장).
 */
@Component
class NativeIndicatorSource : IndicatorSource {
    override val name: String = "native"
    override val priority: Int = 50

    private val supportedNames: Set<String> = setOf("ma", "ema", "rsi", "macd", "bb")

    override fun supports(spec: IndicatorSpec): Boolean = spec.name in supportedNames

    override fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult =
        when (spec.name) {
            "ma" -> NativeMa.calculate(spec, candles)
            "ema" -> NativeEma.calculate(spec, candles)
            "rsi" -> NativeRsi.calculate(spec, candles)
            "macd" -> NativeMacd.calculate(spec, candles)
            "bb" -> NativeBollingerBands.calculate(spec, candles)
            else -> throw IndicatorException.UnsupportedSpecException(spec.name)
        }
}
