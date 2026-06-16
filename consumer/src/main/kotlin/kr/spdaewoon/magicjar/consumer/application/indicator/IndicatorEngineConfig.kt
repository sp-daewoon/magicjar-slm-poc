package kr.spdaewoon.magicjar.consumer.application.indicator

import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * IndicatorEngine bean 등록 — Spring autowire List<IndicatorSource>로 모든 등록된 source 자동 주입.
 *
 * 1차 등록: NativeIndicatorSource(priority=50) + KisStrategyBuilderSource(priority=100).
 * spec §6-2 LOCK + ADR 0021 §2-2 정합.
 *
 * spec §3-2 — consumer 모듈 책임. api 모듈은 별도 IndicatorEngineConfig 등록 (consumer dep 추가).
 *
 * ### 2026-05-15 hotfix — 자본 보호 critical
 * 모든 IndicatorSource 를 [LiveCandleMergingSource] 로 wrap → calculate 진입 전 1d candles list
 * 마지막 row 가 오늘 ts 이면 Redis `candle:today:{code}:1d` 값으로 합류 (replace 또는 add).
 * stale DB close 로 BUY/SELL 잘못 발생 risk 차단.
 */
@Configuration
class IndicatorEngineConfig {
    private val log = LoggerFactory.getLogger(IndicatorEngineConfig::class.java)

    @Bean
    fun indicatorEngine(
        sources: List<IndicatorSource>,
        liveCandleMerger: LiveCandleMerger,
    ): IndicatorEngine {
        // hotfix 2026-05-15: 모든 IndicatorSource 를 LiveCandleMergingSource decorator 로 wrap.
        // 본 wrap 자체가 priority/supports 위임이라 IndicatorEngine 라우팅 결과 불변.
        val decoratedSources = sources.map { LiveCandleMergingSource(it, liveCandleMerger) }
        val engine = IndicatorEngine(decoratedSources)
        log.info(
            "Registered indicator sources (live-candle-merger wrapped): {}",
            engine.describeSources().joinToString(", ") { (n, p) -> "$n (priority=$p)" },
        )
        return engine
    }
}
