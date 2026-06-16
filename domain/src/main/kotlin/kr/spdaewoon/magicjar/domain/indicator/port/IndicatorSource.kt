package kr.spdaewoon.magicjar.domain.indicator.port

import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 지표 source plug-in (ADR 0021 §2-1).
 *
 * 1차 등록 (priority 우선 — 낮을수록 우선):
 *   - [NativeIndicatorSource] (priority=50) — magicJar 자체 5종 (SMA·EMA·RSI·MACD·BB)
 *   - [KisStrategyBuilderSource] (priority=100) — KIS open-trading-api 80지표
 *
 * 동일 spec을 둘 이상이 supports 하면 priority 가장 작은 source 선택.
 *
 * spec §6-2 LOCK.
 */
interface IndicatorSource {
    /**
     * source 식별자 (예: "kis-strategy-builder" · "native" · "ta-lib" · "krx").
     */
    val name: String

    /**
     * 우선순위. 낮을수록 우선. 자체 신뢰성 검증 source가 외부보다 낮은 값 사용 권장.
     */
    val priority: Int

    /**
     * 본 source가 spec을 처리 가능한지 판단.
     */
    fun supports(spec: IndicatorSpec): Boolean

    /**
     * 캔들 시계열 위에서 지표 계산.
     *
     * @throws kr.spdaewoon.magicjar.domain.indicator.IndicatorException.InsufficientCandlesException
     *   캔들 개수가 spec required period 보다 적을 때
     * @throws kr.spdaewoon.magicjar.domain.indicator.IndicatorException.CalculationFailureException
     *   source 내부 계산 실패 시
     */
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult
}
