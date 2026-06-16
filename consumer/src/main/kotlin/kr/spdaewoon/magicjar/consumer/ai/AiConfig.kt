package kr.spdaewoon.magicjar.consumer.ai

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * AI 모듈 Spring 설정 (ADR 0026 §2-6 amend · Resilience4j config).
 *
 * 모든 LLM provider adapter 가 공유:
 * - Retry: 3회 exponential (1s / 2s / 4s) — `aiProviderRetry` Bean
 * - CircuitBreaker: 50% 실패율 → 30s OPEN → HALF_OPEN — `aiProviderCircuitBreaker` Bean
 *
 * Gemini / OpenAI adapter 용 WebClient 도 본 config 에서 발급 (timeout 30s — LLM 평균 응답 시간 정합).
 *
 * Spring AI Anthropic ChatClient.Builder 는 `spring-ai-anthropic-spring-boot-starter` 가 자동 구성 — 본 config 0 touch.
 */
@Configuration
class AiConfig {
    @Bean
    fun aiProviderRetry(retryRegistry: RetryRegistry): Retry {
        val config =
            RetryConfig
                .custom<Any>()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(1), 2.0))
                .retryExceptions(Exception::class.java)
                .build()
        return retryRegistry.retry("ai-provider", config)
    }

    @Bean
    fun aiProviderCircuitBreaker(circuitBreakerRegistry: CircuitBreakerRegistry): CircuitBreaker {
        val config =
            CircuitBreakerConfig
                .custom()
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(2)
                .build()
        return circuitBreakerRegistry.circuitBreaker("ai-provider", config)
    }

    /**
     * Gemini WebClient — Google Generative Language API 호출용.
     * Base URL 은 adapter 에서 path 와 함께 사용.
     */
    @Bean(name = ["geminiWebClient"])
    fun geminiWebClient(): WebClient =
        WebClient
            .builder()
            .baseUrl("https://generativelanguage.googleapis.com")
            .build()

    /**
     * OpenAI WebClient — Chat Completions API 호출용.
     */
    @Bean(name = ["openAiWebClient"])
    fun openAiWebClient(
        @Value("\${magicjar.ai.openai.base-url:https://api.openai.com}") baseUrl: String,
    ): WebClient =
        WebClient
            .builder()
            .baseUrl(baseUrl)
            .build()
}
