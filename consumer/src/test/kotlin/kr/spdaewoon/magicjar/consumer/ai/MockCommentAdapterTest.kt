package kr.spdaewoon.magicjar.consumer.ai

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * MockCommentAdapter — mock fallback 시 한국어 응답 + cost 0 + graceful=false 검증.
 */
class MockCommentAdapterTest {
    private val adapter = MockCommentAdapter(mockMode = "mock")

    @Test
    fun `mock 응답 - 한국어 advisory 포함 + tokens 0 + graceful false`() {
        val request =
            AiCommentRequest(
                type = AiCommentType.MARKET,
                context = mapOf("code" to "005930"),
                userId = 1L,
                requestId = UUID.randomUUID(),
            )
        val response = adapter.comment(request, "test prompt")

        response.provider shouldBe "mock"
        response.model shouldBe "mock-1.0"
        response.tokensIn shouldBe 0
        response.tokensOut shouldBe 0
        response.cached shouldBe false
        response.graceful shouldBe false
        response.content shouldContain "Mock 응답"
        response.content shouldContain "MARKET"
        response.content shouldContain "API key"
        // 매매 결정권 0 (ADR 0023 정합) — mock 응답에도 advisory 명시
        response.content shouldContain "advisory only"
    }

    @Test
    fun `name 은 mock - registry 키와 LOCK`() {
        adapter.name shouldBe "mock"
    }
}
