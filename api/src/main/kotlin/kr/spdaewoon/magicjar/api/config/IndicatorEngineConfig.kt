package kr.spdaewoon.magicjar.api.config

import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * IndicatorEngine Spring 빈 등록 — api 모듈 (spec §3-3 채택 결정 정합).
 *
 * api·consumer 양쪽에 동일 IndicatorEngine 등록 (sources stateless — multi-instance 안전).
 * 1차 sources:
 *   - NativeIndicatorSource   (priority=50,  5종: SMA·EMA·RSI·MACD·BB) — PR-33b backend-core 구현
 *   - KisStrategyBuilderSource (priority=100, 80지표) — PR-33a strategy-engine 구현
 *
 * Spring이 List<IndicatorSource>를 자동 collect (@Component 등록된 모든 구현체).
 * 구현체 없으면 emptyList() fallback → 빈 engine 부팅 (모든 요청 400 — 정상 동작).
 */
@Configuration
class IndicatorEngineConfig {
    private val log = LoggerFactory.getLogger(IndicatorEngineConfig::class.java)

    @Bean
    fun indicatorEngine(sources: List<IndicatorSource>): IndicatorEngine {
        log.info(
            "IndicatorEngine registered sources: [{}]",
            sources
                .sortedBy { it.priority }
                .joinToString(", ") { "${it.name} (priority=${it.priority})" },
        )
        return IndicatorEngine(sources)
    }
}
