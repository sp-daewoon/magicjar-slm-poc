package kr.spdaewoon.magicjar.consumer.application.indicator

import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * IndicatorSource decorator — calculate 진입 전 [LiveCandleMerger]로 1d candles list 정규화.
 *
 * 자본 보호 critical hotfix (2026-05-15):
 *   stale DB 1d close → BUY/SELL 잘못 발생 risk 차단.
 *
 * IndicatorEngine 은 List<IndicatorSource> autowire 후 supports + priority 최소 1건 선택 →
 * 본 decorator 가 native(50) · kis-strategy-builder(100) 둘을 wrap → priority 보존.
 *
 * 헌법 §6 정합 — domain port 추가 0건. consumer 모듈 단일.
 */
class LiveCandleMergingSource(
    private val delegate: IndicatorSource,
    private val merger: LiveCandleMerger,
) : IndicatorSource {
    override val name: String = delegate.name
    override val priority: Int = delegate.priority

    override fun supports(spec: IndicatorSpec): Boolean = delegate.supports(spec)

    override fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val merged = merger.merge(candles)
        return delegate.calculate(spec, merged)
    }
}
