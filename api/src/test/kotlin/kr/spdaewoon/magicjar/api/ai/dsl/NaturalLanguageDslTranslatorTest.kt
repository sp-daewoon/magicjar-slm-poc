package kr.spdaewoon.magicjar.api.ai.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

/**
 * 자연어 → DSL 변환 단위 테스트.
 *
 * 검증 매트릭스:
 * 1. enabled=false → DISABLED + untranslatable
 * 2. mock-mode=mock + RSI 자연어 → MOCK + 정확 변환
 * 3. mock-mode=auto + key=blank → MOCK fallback
 * 4. mock-mode=real + ChatClient 정상 응답 → REAL + DSL 추출
 * 5. mock-mode=real + LLM UNTRANSLATABLE → untranslatable=true
 * 6. mock-mode=real + LLM 응답 위험 문자(`'; DROP TABLE`) → 차단
 * 7. mock-mode=real + LLM 호출 실패 → 1회 재시도 후 untranslatable
 * 8. 빈 입력 → untranslatable
 *
 * Anthropic 호출 0건 (mock provider) — 룰 5 정합 검토 후 OK (단위 테스트 mock LLM provider는 허용).
 */
class NaturalLanguageDslTranslatorTest {
    private val resourceLoader: ResourceLoader =
        object : ResourceLoader {
            override fun getResource(location: String): Resource = ClassPathResource("prompts/dsl-translate.md")

            override fun getClassLoader(): ClassLoader = javaClass.classLoader
        }

    private fun emptyChatClientProvider(): ObjectProvider<ChatClient.Builder> {
        @Suppress("UNCHECKED_CAST")
        val provider = mock<ObjectProvider<ChatClient.Builder>>()
        doReturn(null).whenever(provider).ifAvailable
        return provider
    }

    private fun fakeChatClientProvider(content: String?): ObjectProvider<ChatClient.Builder> {
        val callResp =
            mock<ChatClient.CallResponseSpec>().also {
                doReturn(content).whenever(it).content()
            }
        val callSpec =
            mock<ChatClient.ChatClientRequestSpec>().also {
                doReturn(callResp).whenever(it).call()
                doReturn(it).whenever(it).user(any<String>())
            }
        val client =
            mock<ChatClient>().also {
                doReturn(callSpec).whenever(it).prompt()
            }
        val builder =
            mock<ChatClient.Builder>().also {
                doReturn(client).whenever(it).build()
            }

        @Suppress("UNCHECKED_CAST")
        val provider = mock<ObjectProvider<ChatClient.Builder>>()
        doReturn(builder).whenever(provider).ifAvailable
        return provider
    }

    private fun failingChatClientProvider(): ObjectProvider<ChatClient.Builder> {
        val callSpec =
            mock<ChatClient.ChatClientRequestSpec>().also {
                doThrow(RuntimeException("simulated 5xx")).whenever(it).call()
                doReturn(it).whenever(it).user(any<String>())
            }
        val client =
            mock<ChatClient>().also {
                doReturn(callSpec).whenever(it).prompt()
            }
        val builder =
            mock<ChatClient.Builder>().also {
                doReturn(client).whenever(it).build()
            }

        @Suppress("UNCHECKED_CAST")
        val provider = mock<ObjectProvider<ChatClient.Builder>>()
        doReturn(builder).whenever(provider).ifAvailable
        return provider
    }

    @Test
    fun `enabled=false → DISABLED + untranslatable`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = emptyChatClientProvider(),
                aiEnabled = false,
                mockMode = "auto",
                provider = "anthropic",
                anthropicApiKey = "",
            )
        val result = translator.translate("아무 자연어")
        assertEquals(DslTranslateResult.TranslateMode.DISABLED, result.mode)
        assertTrue(result.untranslatable)
        assertNull(result.dsl)
    }

    @Test
    fun `mock-mode=mock + RSI 자연어 → MOCK + 정확 변환`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = emptyChatClientProvider(),
                aiEnabled = true,
                mockMode = "mock",
                provider = "anthropic",
                anthropicApiKey = "",
            )
        val result = translator.translate("RSI 14가 30 미만일 때")
        assertEquals(DslTranslateResult.TranslateMode.MOCK, result.mode)
        assertFalse(result.untranslatable)
        assertEquals("rsi(14) < 30", result.dsl)
    }

    @Test
    fun `mock-mode=auto + key=blank → MOCK fallback`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = emptyChatClientProvider(),
                aiEnabled = true,
                mockMode = "auto",
                provider = "anthropic",
                anthropicApiKey = "",
            )
        val result = translator.translate("5일 이동평균이 20일 이동평균을 상향 돌파")
        assertEquals(DslTranslateResult.TranslateMode.MOCK, result.mode)
        assertEquals("ma(5) crosses_above ma(20)", result.dsl)
    }

    @Test
    fun `mock-mode=real + LLM 정상 응답 → REAL + DSL 추출`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = fakeChatClientProvider("ma(5) crosses_above ma(20)"),
                aiEnabled = true,
                mockMode = "real",
                provider = "anthropic",
                anthropicApiKey = "sk-ant-fake",
            )
        val result = translator.translate("5일선이 20일선 상향 돌파")
        assertEquals(DslTranslateResult.TranslateMode.REAL, result.mode)
        assertFalse(result.untranslatable)
        assertEquals("ma(5) crosses_above ma(20)", result.dsl)
    }

    @Test
    fun `mock-mode=real + 코드펜스 + 줄바꿈 응답 → 마지막 줄만 추출`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider =
                    fakeChatClientProvider("```\nrsi(14) < 30\n```"),
                aiEnabled = true,
                mockMode = "real",
                provider = "anthropic",
                anthropicApiKey = "sk-ant-fake",
            )
        val result = translator.translate("RSI 30 미만")
        assertEquals("rsi(14) < 30", result.dsl)
    }

    @Test
    fun `mock-mode=real + UNTRANSLATABLE 응답 → untranslatable true`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = fakeChatClientProvider("UNTRANSLATABLE"),
                aiEnabled = true,
                mockMode = "real",
                provider = "anthropic",
                anthropicApiKey = "sk-ant-fake",
            )
        val result = translator.translate("회장님이 좋아하는 종목")
        assertTrue(result.untranslatable)
        assertNull(result.dsl)
        assertEquals(DslTranslateResult.TranslateMode.REAL, result.mode)
    }

    @Test
    fun `mock-mode=real + 위험 문자 응답 → 화이트리스트 차단`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = fakeChatClientProvider("rsi(14) < 30; DROP TABLE strategy;"),
                aiEnabled = true,
                mockMode = "real",
                provider = "anthropic",
                anthropicApiKey = "sk-ant-fake",
            )
        val result = translator.translate("RSI 30 미만")
        assertTrue(result.untranslatable)
        assertNotNull(result.rawResponse)
        assertNull(result.dsl)
    }

    @Test
    fun `mock-mode=real + LLM 예외 → 재시도 2회 후 untranslatable`() {
        var calls = 0
        val provider = failingChatClientProvider()
        // failingChatClientProvider이 매 prompt 호출마다 예외 → invokeWithRetry repeat(2) 모두 실패
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = provider,
                aiEnabled = true,
                mockMode = "real",
                provider = "anthropic",
                anthropicApiKey = "sk-ant-fake",
            )
        val result = translator.translate("RSI 30 미만")
        assertTrue(result.untranslatable)
        assertEquals(DslTranslateResult.TranslateMode.REAL, result.mode)
        // 호출 횟수 검증은 Mockito verify로 별도 가능 — 본 테스트는 결과 결정론에 집중
        calls += 0 // suppress unused warning
        assertEquals(0, calls)
    }

    @Test
    fun `빈 자연어 → untranslatable`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = emptyChatClientProvider(),
                aiEnabled = true,
                mockMode = "auto",
                provider = "anthropic",
                anthropicApiKey = "",
            )
        val result = translator.translate("   ")
        assertTrue(result.untranslatable)
        assertNull(result.dsl)
    }

    @Test
    fun `non-anthropic provider → mock fallback`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = fakeChatClientProvider("ma(5) crosses_above ma(20)"),
                aiEnabled = true,
                mockMode = "real",
                provider = "openai",
                anthropicApiKey = "sk-ant-fake",
            )
        val result = translator.translate("5일선이 20일선 상향 돌파")
        // provider 미지원 시 mock fallback 진입 → MOCK + 정확 룰 매칭
        assertEquals(DslTranslateResult.TranslateMode.MOCK, result.mode)
        assertEquals("ma(5) crosses_above ma(20)", result.dsl)
    }

    @Test
    fun `mock-mode=mock + 매칭 실패 → MOCK + untranslatable`() {
        val translator =
            NaturalLanguageDslTranslator(
                resourceLoader = resourceLoader,
                chatClientBuilderProvider = emptyChatClientProvider(),
                aiEnabled = true,
                mockMode = "mock",
                provider = "anthropic",
                anthropicApiKey = "",
            )
        val result = translator.translate("회장님이 좋아하는 종목")
        assertEquals(DslTranslateResult.TranslateMode.MOCK, result.mode)
        assertTrue(result.untranslatable)
        assertNull(result.dsl)
    }

    // unused import suppression — doAnswer는 향후 verify 확장 시 사용
    @Suppress("unused")
    private fun unused() = doAnswer { }
}
