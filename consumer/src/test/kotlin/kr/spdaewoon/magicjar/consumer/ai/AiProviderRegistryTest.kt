package kr.spdaewoon.magicjar.consumer.ai

import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * AiProviderRegistry — env provider 매칭 + fallback 검증.
 */
class AiProviderRegistryTest {
    private fun stubProvider(providerName: String): AiCommentProvider =
        object : AiCommentProvider {
            override val name: String = providerName
            override val model: String = "$providerName-model"

            override fun comment(
                request: AiCommentRequest,
                prompt: String,
            ): AiCommentResponse =
                AiCommentResponse(
                    requestId = UUID.randomUUID(),
                    provider = providerName,
                    model = model,
                    content = "",
                    tokensIn = 0,
                    tokensOut = 0,
                    latencyMs = 0,
                )
        }

    @Test
    fun `configured provider 매칭 - anthropic`() {
        val registry =
            AiProviderRegistry(
                providers = listOf(stubProvider("anthropic"), stubProvider("gemini")),
                configuredProvider = "anthropic",
            )
        registry.resolve().name shouldBe "anthropic"
    }

    @Test
    fun `configured provider 매칭 - gemini`() {
        val registry =
            AiProviderRegistry(
                providers = listOf(stubProvider("anthropic"), stubProvider("gemini"), stubProvider("openai")),
                configuredProvider = "gemini",
            )
        registry.resolve().name shouldBe "gemini"
    }

    @Test
    fun `configured provider 매칭 - claude-cli`() {
        val registry =
            AiProviderRegistry(
                providers = listOf(stubProvider("anthropic"), stubProvider("claude-cli")),
                configuredProvider = "claude-cli",
            )
        registry.resolve().name shouldBe "claude-cli"
    }

    @Test
    fun `미존재 provider - anthropic fallback`() {
        val registry =
            AiProviderRegistry(
                providers = listOf(stubProvider("anthropic"), stubProvider("gemini")),
                configuredProvider = "claude4",
            )
        registry.resolve().name shouldBe "anthropic"
    }
}
