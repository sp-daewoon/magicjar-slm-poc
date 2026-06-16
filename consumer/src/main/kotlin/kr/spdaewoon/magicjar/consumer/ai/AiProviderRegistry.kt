package kr.spdaewoon.magicjar.consumer.ai

import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * AI provider registry — env `MAGICJAR_AI_PROVIDER` 기반 adapter 선택.
 *
 * 확장성 (ADR 0026 §3 amend):
 * - 4번째 provider 추가 시 본 registry 변경 0 — `List<AiCommentProvider>` 자동 collect.
 * - 새 adapter 는 `AiCommentProvider.name` 만 지정하면 됨.
 *
 * graceful fallback:
 * - 설정된 provider 가 misconfigured (API key 미설정 등) → adapter 가 mock 응답 반환 책임
 * - 설정된 provider name 이 registry 에 없음 → 1차 default (anthropic) fallback + WARN log
 */
@Component
class AiProviderRegistry(
    private val providers: List<AiCommentProvider>,
    @Value("\${magicjar.ai.provider:anthropic}") private val configuredProvider: String,
) {
    private val log = LoggerFactory.getLogger(AiProviderRegistry::class.java)

    private val byName: Map<String, AiCommentProvider> by lazy {
        providers.associateBy { it.name.lowercase() }
    }

    fun resolve(): AiCommentProvider {
        val key = configuredProvider.lowercase()
        val provider = byName[key]
        if (provider != null) return provider
        log.warn(
            "[AiProviderRegistry] configured provider 미존재 — configured={} available={} fallback=anthropic",
            configuredProvider,
            byName.keys,
        )
        return byName["anthropic"]
            ?: providers.firstOrNull()
            ?: error("AiCommentProvider Bean 0건 — consumer/ai/ adapter 등록 확인")
    }
}
