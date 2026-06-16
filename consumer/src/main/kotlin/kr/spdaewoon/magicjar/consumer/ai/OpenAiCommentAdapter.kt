package kr.spdaewoon.magicjar.consumer.ai

import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

/**
 * OpenAI GPT provider (ADR 0026 §2-1 amend · 백업 옵션).
 *
 * 활성 시점: `MAGICJAR_AI_PROVIDER=openai` + `OPENAI_API_KEY` 설정.
 *
 * 모델:
 * - default: `gpt-4o-mini` — 빠르고 저렴 (input $0.15 / output $0.60 per 1M)
 * - env override: `magicjar.ai.openai.model`
 *
 * 호출:
 * - REST API: `POST /v1/chat/completions` (Bearer auth)
 * - 직접 JSON 직렬화 (Spring AI OpenAI starter 미적용 — adapter 단일 책임 + 의존성 최소화)
 *
 * 응답 schema: `choices[0].message.content` + `usage.prompt_tokens/completion_tokens`.
 */
@Component
class OpenAiCommentAdapter(
    @Qualifier("openAiWebClient") private val webClient: WebClient,
    @Value("\${magicjar.ai.openai.api-key:}") private val apiKey: String,
    @Value("\${magicjar.ai.openai.model:gpt-4o-mini}") private val configuredModel: String,
    @Value("\${magicjar.ai.openai.timeout-ms:30000}") private val timeoutMs: Long,
) : AiCommentProvider {
    private val log = LoggerFactory.getLogger(OpenAiCommentAdapter::class.java)

    override val name: String = "openai"
    override val model: String get() = configuredModel

    override fun comment(
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OPENAI_API_KEY 미설정")
        }
        val started = System.currentTimeMillis()
        val body =
            mapOf(
                "model" to configuredModel,
                "messages" to
                    listOf(
                        mapOf("role" to "user", "content" to prompt),
                    ),
            )
        val response: Map<*, *> =
            webClient
                .post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono<Map<*, *>>()
                .block(Duration.ofMillis(timeoutMs))
                ?: throw IllegalStateException("OpenAI 응답 null")

        val content = extractContent(response)
        val (tokensIn, tokensOut) = extractUsage(response)
        val latencyMs = System.currentTimeMillis() - started

        log.debug(
            "[OpenAiAdapter] OK — requestId={} type={} tokensIn={} tokensOut={} latencyMs={}",
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

    @Suppress("UNCHECKED_CAST")
    private fun extractContent(response: Map<*, *>): String {
        val choices = response["choices"] as? List<Map<String, Any?>> ?: return ""
        val first = choices.firstOrNull() ?: return ""
        val message = first["message"] as? Map<String, Any?> ?: return ""
        return message["content"]?.toString()?.trim() ?: ""
    }

    private fun extractUsage(response: Map<*, *>): Pair<Int, Int> {
        val usage = response["usage"] as? Map<*, *> ?: return 0 to 0
        val tokensIn = (usage["prompt_tokens"] as? Number)?.toInt() ?: 0
        val tokensOut = (usage["completion_tokens"] as? Number)?.toInt() ?: 0
        return tokensIn to tokensOut
    }
}
