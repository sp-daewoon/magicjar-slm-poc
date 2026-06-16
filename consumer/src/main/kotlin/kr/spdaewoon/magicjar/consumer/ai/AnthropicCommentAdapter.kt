package kr.spdaewoon.magicjar.consumer.ai

import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Anthropic Claude provider (ADR 0026 §2-1 amend · 1차 default).
 *
 * 활성 시점: `MAGICJAR_AI_PROVIDER=anthropic` + `ANTHROPIC_API_KEY` 설정.
 *
 * 모델:
 * - default: `claude-3-5-haiku-20241022` — 빠르고 저렴 (input $0.80 / output $4.00 per 1M)
 * - env override: `magicjar.ai.anthropic.model`
 *
 * 의존:
 * - `spring-ai-anthropic-spring-boot-starter` 가 `ChatClient.Builder` 자동 구성
 * - api key 미설정 시 ChatClient.Builder Bean 미생성 → ObjectProvider null fallback → mock 응답
 *
 * Resilience4j 통합:
 * - 본 adapter 는 raw call — Retry / CircuitBreaker 적용은 `AiCommentDispatcherListener` 가 합성
 *   (provider 별 별도 적용보다 dispatcher level 1회 적용이 simpler + 통계 단일화)
 */
@Component
class AnthropicCommentAdapter(
    private val chatClientBuilderProvider: ObjectProvider<ChatClient.Builder>,
    @Value("\${spring.ai.anthropic.api-key:}") private val anthropicApiKey: String,
    @Value("\${magicjar.ai.anthropic.model:claude-3-5-haiku-20241022}") private val configuredModel: String,
) : AiCommentProvider {
    private val log = LoggerFactory.getLogger(AnthropicCommentAdapter::class.java)

    override val name: String = "anthropic"
    override val model: String get() = configuredModel

    private val chatClient: ChatClient? by lazy {
        if (anthropicApiKey.isBlank()) {
            log.warn("[AnthropicAdapter] ANTHROPIC_API_KEY 미설정 — null client (mock fallback)")
            null
        } else {
            runCatching { chatClientBuilderProvider.ifAvailable?.build() }
                .onFailure { log.warn("[AnthropicAdapter] ChatClient build 실패: {}", it.message) }
                .getOrNull()
        }
    }

    override fun comment(
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse {
        val client =
            chatClient
                ?: throw IllegalStateException("Anthropic ChatClient unavailable (API key 미설정 또는 build 실패)")

        val started = System.currentTimeMillis()
        val response =
            client
                .prompt()
                .user(prompt)
                .call()
                .chatResponse()
                ?: throw IllegalStateException("Anthropic chatResponse() null")
        // Spring AI 1.0.0-M3 — Generation.getOutput() = AssistantMessage / AbstractMessage.getContent() = text
        val content = response.result?.output?.content ?: ""

        // Spring AI 1.0.0-M3 — Usage interface: getPromptTokens() · getGenerationTokens()
        val usage = response.metadata?.usage
        val tokensIn = usage?.promptTokens?.toInt() ?: 0
        val tokensOut = usage?.generationTokens?.toInt() ?: 0
        val latencyMs = System.currentTimeMillis() - started

        log.debug(
            "[AnthropicAdapter] OK — requestId={} type={} tokensIn={} tokensOut={} latencyMs={}",
            request.requestId,
            request.type,
            tokensIn,
            tokensOut,
            latencyMs,
        )

        return AiCommentResponse(
            requestId = request.requestId,
            provider = name,
            model = model,
            content = content,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            latencyMs = latencyMs,
            cached = false,
            graceful = false,
        )
    }
}
