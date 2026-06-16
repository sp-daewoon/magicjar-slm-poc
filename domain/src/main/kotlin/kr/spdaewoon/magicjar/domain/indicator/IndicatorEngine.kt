package kr.spdaewoon.magicjar.domain.indicator

import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 지표 계산 라우터.
 *
 * 등록된 [IndicatorSource] 후보 중 [IndicatorSource.supports] 통과 + [IndicatorSource.priority] 가장 작은 source 선택.
 * 미지원 spec은 [IndicatorException.UnsupportedSpecException] 으로 즉시 실패.
 *
 * Spring 의존 0 (도메인 순수). bean wiring은 consumer/api 모듈의 IndicatorEngineConfig에서 수행.
 *
 * ADR 0021 §2-2 정합. spec §6-2 LOCK.
 */
class IndicatorEngine(
    private val sources: List<IndicatorSource>,
) {
    /**
     * spec 라우팅.
     *
     * @throws IndicatorException.UnsupportedSpecException supports 통과 source 0건일 때
     */
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult {
        val source =
            sources
                .filter { it.supports(spec) }
                .minByOrNull { it.priority }
                ?: throw IndicatorException.UnsupportedSpecException(spec.name)
        return source.calculate(spec, candles)
    }

    /**
     * 등록된 source 메타 (디버깅 · 부팅 로그용).
     */
    fun describeSources(): List<Pair<String, Int>> = sources.map { it.name to it.priority }.sortedBy { it.second }

    /**
     * 등록된 source 중 본 spec을 지원하는 source 1건의 식별자 반환 (없으면 null).
     */
    fun resolveSourceName(spec: IndicatorSpec): String? = sources.filter { it.supports(spec) }.minByOrNull { it.priority }?.name
}
