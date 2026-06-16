package kr.spdaewoon.magicjar.api.config

import kr.spdaewoon.magicjar.api.infrastructure.observability.TraceIdWebClientFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * WebClient Bean 설정 (ADR 0046 §4-6).
 * KisAuthValidator 등 외부 REST 호출에 사용.
 * 운영용 KisAuthAdapter (Redis 분산락 + 토큰 cache)는 PR-7+ T-W2에서 별도 구성.
 *
 * ### MDC traceId 자동 부착 (ADR 0084 P1-3)
 * [TraceIdWebClientFilter] 등록 — 모든 outbound HTTP 호출 헤더에 현재 thread `traceId` 부착.
 * KIS·DART·Naver 호출 시 magicJar 내부 audit log chain 추적 가능.
 */
@Configuration
class WebClientConfig {
    @Bean
    fun webClient(traceIdWebClientFilter: TraceIdWebClientFilter): WebClient =
        WebClient
            .builder()
            .filter(traceIdWebClientFilter)
            .build()
}
