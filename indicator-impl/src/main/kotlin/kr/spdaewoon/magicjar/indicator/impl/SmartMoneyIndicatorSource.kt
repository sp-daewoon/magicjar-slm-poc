package kr.spdaewoon.magicjar.indicator.impl

import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.smartmoney.SmartMoneyIndicators
import org.springframework.stereotype.Component

/**
 * FVG / Order Block 지표 source.
 *
 * TradingView/ICT 계열의 주관적 개념을 시스템 자동매매에서 재현 가능하게 쓰기 위해
 * deterministic rule set으로 제한한다.
 */
@Component
class SmartMoneyIndicatorSource : IndicatorSource {
    override val name: String = "smart-money"
    override val priority: Int = 60

    private val supportedNames = setOf("fvg", "order_block")

    override fun supports(spec: IndicatorSpec): Boolean = spec.name in supportedNames

    override fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult =
        try {
            when (spec.name) {
                "fvg" -> SmartMoneyIndicators.fvg(spec, candles)
                "order_block" -> SmartMoneyIndicators.orderBlock(spec, candles)
                else -> throw IndicatorException.UnsupportedSpecException(spec.name)
            }
        } catch (e: IndicatorException) {
            throw e
        } catch (e: Throwable) {
            throw IndicatorException.CalculationFailureException(spec, e)
        }
}
