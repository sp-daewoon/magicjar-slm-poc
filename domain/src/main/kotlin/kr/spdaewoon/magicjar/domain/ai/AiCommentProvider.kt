package kr.spdaewoon.magicjar.domain.ai

/**
 * AI 코멘트 provider port (ADR 0026 §2-3 amend 정합).
 *
 * 구현체 (consumer 모듈):
 * - `AnthropicCommentAdapter` (1차 default · Spring AI ChatClient · `MAGICJAR_AI_PROVIDER=anthropic`)
 * - `GeminiCommentAdapter` (free tier 옵션 · WebClient 직접 호출 · `MAGICJAR_AI_PROVIDER=gemini`)
 * - `OpenAiCommentAdapter` (백업 옵션 · WebClient 직접 호출 · `MAGICJAR_AI_PROVIDER=openai`)
 *
 * 확장성 (ADR 0026 §3 amend):
 * - 4번째 provider 추가 시 본 interface 변경 0 — 새 adapter 추가 + `AiProviderRegistry.byName()` 자동 collect.
 *
 * 실패 처리 (ADR 0026 §2-6 정합):
 * - LLM 호출 실패·타임아웃 → Resilience4j Retry 3회 (1s/2s/4s exponential) → Circuit Breaker 30s OPEN
 * - 모든 실패 후에도 본체 흐름 영향 0 — 호출자가 graceful refuse response 합성
 */
interface AiCommentProvider {
    /**
     * provider 식별자 — env `MAGICJAR_AI_PROVIDER` 값과 매칭.
     * (예: "anthropic" / "gemini" / "openai")
     */
    val name: String

    /**
     * 사용 중인 모델 식별자 — `ai_comment_log.model` 컬럼 기록용.
     * (예: "claude-3-5-haiku-20241022" / "gemini-1.5-flash" / "gpt-4o-mini")
     */
    val model: String

    /**
     * LLM 호출 + 응답 반환.
     *
     * @param request 도메인 요청 (type · context · userId · requestId)
     * @param prompt 변수 치환 완료된 최종 prompt 문자열 (PromptTemplateResolver 출력)
     * @return AiCommentResponse — `cached=false` (캐시는 호출자 책임)
     * @throws RuntimeException provider 호출 실패 (Resilience4j 가 catch → retry / open)
     */
    fun comment(
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse
}
