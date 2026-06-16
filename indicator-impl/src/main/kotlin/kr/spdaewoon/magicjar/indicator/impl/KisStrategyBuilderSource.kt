package kr.spdaewoon.magicjar.indicator.impl

import kr.spdaewoon.magicjar.domain.indicator.IndicatorCatalog
import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.indicator.impl.kis.IndicatorMomentum
import kr.spdaewoon.magicjar.indicator.impl.kis.IndicatorOscillator
import kr.spdaewoon.magicjar.indicator.impl.kis.IndicatorOther
import kr.spdaewoon.magicjar.indicator.impl.kis.IndicatorTrend
import kr.spdaewoon.magicjar.indicator.impl.kis.IndicatorVolatility
import kr.spdaewoon.magicjar.indicator.impl.kis.IndicatorVolume
import org.springframework.stereotype.Component

/**
 * KIS Strategy Builder 80지표 source (ADR 0020 §3-1·§3-4 + ADR 0021 §2-3).
 *
 * Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py
 * License: MIT
 *
 * 80개 함수 = TREND 20 + MOMENTUM 20 + VOLUME 12 + VOLATILITY 12 + OSCILLATOR 8 + OTHER 8.
 * 각 분류 파일 내 helper에 KIS Python 함수 1:1 정합 구현 (KDoc raw 출처 줄번호 인용 의무 — 헌법 §3 KIS-first).
 *
 * priority=100 — Native(50)보다 후순위. Native 활성 영역(SMA/EMA/RSI/MACD/BB)은 KIS 호출 안 됨.
 */
@Component
class KisStrategyBuilderSource : IndicatorSource {
    override val name: String = "kis-strategy-builder"
    override val priority: Int = 100

    override fun supports(spec: IndicatorSpec): Boolean = spec.name in IndicatorCatalog.ALL_IDS && spec.name !in SMART_MONEY_IDS

    override fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult =
        try {
            when (spec.name) {
                // ===== TREND 20 =====
                "ma" -> IndicatorTrend.ma(spec, candles)
                "ema" -> IndicatorTrend.ema(spec, candles)
                "wma" -> IndicatorTrend.wma(spec, candles)
                "hma" -> IndicatorTrend.hma(spec, candles)
                "dema" -> IndicatorTrend.dema(spec, candles)
                "tema" -> IndicatorTrend.tema(spec, candles)
                "trima" -> IndicatorTrend.trima(spec, candles)
                "lwma" -> IndicatorTrend.lwma(spec, candles)
                "t3" -> IndicatorTrend.t3(spec, candles)
                "zlema" -> IndicatorTrend.zlema(spec, candles)
                "kama" -> IndicatorTrend.kama(spec, candles)
                "alma" -> IndicatorTrend.alma(spec, candles)
                "frama" -> IndicatorTrend.frama(spec, candles)
                "vidya" -> IndicatorTrend.vidya(spec, candles)
                "macd" -> IndicatorTrend.macd(spec, candles)
                "ichimoku_tenkan" -> IndicatorTrend.ichimokuTenkan(spec, candles)
                "ichimoku_kijun" -> IndicatorTrend.ichimokuKijun(spec, candles)
                "supertrend" -> IndicatorTrend.supertrend(spec, candles)
                "sar" -> IndicatorTrend.sar(spec, candles)
                "regression_slope" -> IndicatorTrend.regressionSlope(spec, candles)

                // ===== MOMENTUM 20 =====
                "rsi" -> IndicatorMomentum.rsi(spec, candles)
                "stochastic_k" -> IndicatorMomentum.stochasticK(spec, candles)
                "stochastic_d" -> IndicatorMomentum.stochasticD(spec, candles)
                "stochrsi" -> IndicatorMomentum.stochrsi(spec, candles)
                "cci" -> IndicatorMomentum.cci(spec, candles)
                "williams_r" -> IndicatorMomentum.williamsR(spec, candles)
                "roc" -> IndicatorMomentum.roc(spec, candles)
                "momentum" -> IndicatorMomentum.momentum(spec, candles)
                "apo" -> IndicatorMomentum.apo(spec, candles)
                "ppo" -> IndicatorMomentum.ppo(spec, candles)
                "cmo" -> IndicatorMomentum.cmo(spec, candles)
                "ao" -> IndicatorMomentum.ao(spec, candles)
                "trix" -> IndicatorMomentum.trix(spec, candles)
                "tsi" -> IndicatorMomentum.tsi(spec, candles)
                "rvi" -> IndicatorMomentum.rvi(spec, candles)
                "dpo" -> IndicatorMomentum.dpo(spec, candles)
                "kst" -> IndicatorMomentum.kst(spec, candles)
                "coppock" -> IndicatorMomentum.coppock(spec, candles)
                "schaff" -> IndicatorMomentum.schaff(spec, candles)
                "fisher" -> IndicatorMomentum.fisher(spec, candles)

                // ===== VOLUME 12 =====
                "obv" -> IndicatorVolume.obv(spec, candles)
                "volume_ma" -> IndicatorVolume.volumeMa(spec, candles)
                "vwap" -> IndicatorVolume.vwap(spec, candles)
                "mfi" -> IndicatorVolume.mfi(spec, candles)
                "cmf" -> IndicatorVolume.cmf(spec, candles)
                "cho" -> IndicatorVolume.cho(spec, candles)
                "ad" -> IndicatorVolume.ad(spec, candles)
                "adl" -> IndicatorVolume.adl(spec, candles)
                "force" -> IndicatorVolume.force(spec, candles)
                "vwma" -> IndicatorVolume.vwma(spec, candles)
                "eom" -> IndicatorVolume.eom(spec, candles)
                "kvo" -> IndicatorVolume.kvo(spec, candles)

                // ===== VOLATILITY 12 =====
                "bb" -> IndicatorVolatility.bb(spec, candles)
                "bb_width" -> IndicatorVolatility.bbWidth(spec, candles)
                "bb_percent" -> IndicatorVolatility.bbPercent(spec, candles)
                "atr" -> IndicatorVolatility.atr(spec, candles)
                "natr" -> IndicatorVolatility.natr(spec, candles)
                "keltner_upper" -> IndicatorVolatility.keltnerUpper(spec, candles)
                "keltner_lower" -> IndicatorVolatility.keltnerLower(spec, candles)
                "donchian_upper" -> IndicatorVolatility.donchianUpper(spec, candles)
                "donchian_lower" -> IndicatorVolatility.donchianLower(spec, candles)
                "chop" -> IndicatorVolatility.chop(spec, candles)
                "mass_index" -> IndicatorVolatility.massIndex(spec, candles)
                "augen" -> IndicatorVolatility.augen(spec, candles)

                // ===== OSCILLATOR 8 =====
                "adx" -> IndicatorOscillator.adx(spec, candles)
                "adxr" -> IndicatorOscillator.adxr(spec, candles)
                "aroon_up" -> IndicatorOscillator.aroonUp(spec, candles)
                "aroon_down" -> IndicatorOscillator.aroonDown(spec, candles)
                "vortex_plus" -> IndicatorOscillator.vortexPlus(spec, candles)
                "vortex_minus" -> IndicatorOscillator.vortexMinus(spec, candles)
                "ultosc" -> IndicatorOscillator.ultosc(spec, candles)
                "bop" -> IndicatorOscillator.bop(spec, candles)

                // ===== OTHER 8 =====
                "std" -> IndicatorOther.std(spec, candles)
                "variance" -> IndicatorOther.variance(spec, candles)
                "disparity" -> IndicatorOther.disparity(spec, candles)
                "midpoint" -> IndicatorOther.midpoint(spec, candles)
                "midprice" -> IndicatorOther.midprice(spec, candles)
                "logr" -> IndicatorOther.logr(spec, candles)
                "pivot" -> IndicatorOther.pivot(spec, candles)
                "regression_intercept" -> IndicatorOther.regressionIntercept(spec, candles)

                else -> throw IndicatorException.UnsupportedSpecException(spec.name)
            }
        } catch (e: IndicatorException) {
            throw e
        } catch (e: Throwable) {
            throw IndicatorException.CalculationFailureException(spec, e)
        }

    private companion object {
        val SMART_MONEY_IDS = setOf("fvg", "order_block")
    }
}
