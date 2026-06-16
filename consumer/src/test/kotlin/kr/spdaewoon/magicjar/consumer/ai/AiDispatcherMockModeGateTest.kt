package kr.spdaewoon.magicjar.consumer.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * decideMockMode — claude-cli 키 불요 게이트 + 기존 provider 게이트 회귀 검증.
 *
 * 핵심: claude-cli 는 키가 없어도 auto 모드에서 mock 으로 강등되지 않는다 (실해석 경로).
 * 기존 anthropic/gemini/openai 게이트는 무수정 — 키 미설정 시 auto=mock 보존.
 */
class AiDispatcherMockModeGateTest {
    @Test
    fun `claude-cli - auto + 모든 키 미설정이어도 mock 아님`() {
        decideMockMode(
            mockMode = "auto",
            provider = "claude-cli",
            anthropicKeyBlank = true,
            geminiKeyBlank = true,
            openAiKeyBlank = true,
        ) shouldBe false
    }

    @Test
    fun `anthropic - auto + 키 미설정이면 mock (기존 동작 보존)`() {
        decideMockMode(
            mockMode = "auto",
            provider = "anthropic",
            anthropicKeyBlank = true,
            geminiKeyBlank = true,
            openAiKeyBlank = true,
        ) shouldBe true
    }

    @Test
    fun `anthropic - auto + 키 설정이면 mock 아님 (기존 동작 보존)`() {
        decideMockMode(
            mockMode = "auto",
            provider = "anthropic",
            anthropicKeyBlank = false,
            geminiKeyBlank = true,
            openAiKeyBlank = true,
        ) shouldBe false
    }

    @Test
    fun `mock 강제 - claude-cli 라도 mock`() {
        decideMockMode(
            mockMode = "mock",
            provider = "claude-cli",
            anthropicKeyBlank = true,
            geminiKeyBlank = true,
            openAiKeyBlank = true,
        ) shouldBe true
    }

    @Test
    fun `real 강제 - claude-cli mock 아님`() {
        decideMockMode(
            mockMode = "real",
            provider = "claude-cli",
            anthropicKeyBlank = true,
            geminiKeyBlank = true,
            openAiKeyBlank = true,
        ) shouldBe false
    }
}
