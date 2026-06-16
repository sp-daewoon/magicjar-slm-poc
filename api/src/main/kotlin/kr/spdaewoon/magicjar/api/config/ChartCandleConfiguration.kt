package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 차트 캔들 properties wiring (ADR 0093 §3-1 LOCK).
 *
 * `@EnableConfigurationProperties(ChartCandleProperties::class)` 단일 책임 — 별도 Bean 0.
 * Spring Boot 3.3 `@ConfigurationProperties` constructor binding 자동 적용.
 */
@Configuration
@EnableConfigurationProperties(ChartCandleProperties::class)
class ChartCandleConfiguration
