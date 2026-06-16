package kr.spdaewoon.magicjar.consumer.ai

import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Mock provider — `MAGICJAR_AI_MOCK_MODE` 분기 fallback (ADR 0026 §2-5 amend).
 *
 * 활성 시점:
 * - `MAGICJAR_AI_MOCK_MODE=mock` (강제 mock)
 * - `MAGICJAR_AI_MOCK_MODE=auto` + 모든 provider key 미설정 (auto fallback)
 *
 * 응답:
 * - 한국어 advisory + type 별 placeholder 메시지 (frontend AiCard 정상 렌더링 검증)
 * - tokens=0 / cost=0 / cached=false / graceful=false
 * - provider="mock" / model="mock-1.0"
 *
 * 본 adapter 는 `AiProviderRegistry` 가 mock 모드 분기 시 직접 호출 — 일반 provider 목록과 분리.
 */
@Component
class MockCommentAdapter(
    @Value("\${magicjar.ai.mock-mode:auto}") private val mockMode: String,
) : AiCommentProvider {
    private val log = LoggerFactory.getLogger(MockCommentAdapter::class.java)

    override val name: String = "mock"
    override val model: String = "mock-1.0"

    override fun comment(
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse {
        log.info(
            "[MockAdapter] mock 응답 — requestId={} type={} mockMode={} promptLen={}",
            request.requestId,
            request.type,
            mockMode,
            prompt.length,
        )
        val content =
            """
            [Mock 응답 — provider 미설정 또는 mock 강제 모드]

            ${request.type.name} 분석을 mock 결과로 응답합니다.

            • 시장 위치: 데이터 부족 (실제 LLM 응답 시 컨텍스트 기반 분석)
            • 흐름 평가: 데이터 부족 (실제 응답 시 지표·뉴스 종합 평가)
            • 주의 사항: 본 응답은 mock 입니다. 실제 활용 전 .env 의 API key (ANTHROPIC_API_KEY / GEMINI_API_KEY / OPENAI_API_KEY) 설정 후 재시도 해 주세요.

            매매 결정권 0 — advisory only.
            """.trimIndent()
        return AiCommentResponse(
            requestId = request.requestId,
            provider = name,
            model = model,
            content = content,
            tokensIn = 0,
            tokensOut = 0,
            latencyMs = 0L,
            cached = false,
            graceful = false,
        )
    }
}
