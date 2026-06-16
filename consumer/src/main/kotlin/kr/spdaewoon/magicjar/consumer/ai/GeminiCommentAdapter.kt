package kr.spdaewoon.magicjar.consumer.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

/**
 * Google Gemini provider (ADR 0026 §2-1 amend · free tier 옵션).
 *
 * 활성 시점: `MAGICJAR_AI_PROVIDER=gemini` + `GEMINI_API_KEY` 설정.
 *
 * 모델:
 * - default: `gemini-1.5-flash` — free tier 한도 (분당 15 RPM · 일 1500 요청 · 입력 1M 토큰 무료)
 * - 정식 비용: input $0.075 / output $0.30 per 1M
 *
 * 호출:
 * - REST API: `POST /v1beta/models/{model}:generateContent?key={apiKey}`
 * - 직접 JSON 직렬화 (Spring AI Gemini 의존성 회피 — google-cloud-vertex SDK 무거움)
 *
 * 응답 schema: `candidates[0].content.parts[0].text` + `usageMetadata.promptTokenCount/candidatesTokenCount`.
 */
@Component
class GeminiCommentAdapter(
    @Qualifier("geminiWebClient") private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.ai.gemini.api-key:}") private val apiKey: String,
    @Value("\${magicjar.ai.gemini.model:gemini-1.5-flash}") private val configuredModel: String,
    @Value("\${magicjar.ai.gemini.timeout-ms:30000}") private val timeoutMs: Long,
) : AiCommentProvider {
    private val log = LoggerFactory.getLogger(GeminiCommentAdapter::class.java)

    override val name: String = "gemini"
    override val model: String get() = configuredModel

    override fun comment(
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse {
        if (apiKey.isBlank()) {
            throw IllegalStateException("GEMINI_API_KEY 미설정")
        }
        val started = System.currentTimeMillis()
        val body =
            mapOf(
                "contents" to
                    listOf(
                        mapOf(
                            "parts" to listOf(mapOf("text" to prompt)),
                        ),
                    ),
            )
        val response: Map<*, *> =
            webClient
                .post()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/v1beta/models/$configuredModel:generateContent")
                        .queryParam("key", apiKey)
                        .build()
                }.contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono<Map<*, *>>()
                .block(Duration.ofMillis(timeoutMs))
                ?: throw IllegalStateException("Gemini 응답 null")

        val content = extractContent(response)
        val (tokensIn, tokensOut) = extractUsage(response)
        val latencyMs = System.currentTimeMillis() - started

        log.debug(
            "[GeminiAdapter] OK — requestId={} type={} tokensIn={} tokensOut={} latencyMs={}",
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
        val candidates = response["candidates"] as? List<Map<String, Any?>> ?: return ""
        val first = candidates.firstOrNull() ?: return ""
        val content = first["content"] as? Map<String, Any?> ?: return ""
        val parts = content["parts"] as? List<Map<String, Any?>> ?: return ""
        return parts.mapNotNull { it["text"]?.toString() }.joinToString("\n").trim()
    }

    private fun extractUsage(response: Map<*, *>): Pair<Int, Int> {
        val usage = response["usageMetadata"] as? Map<*, *> ?: return 0 to 0
        val tokensIn = (usage["promptTokenCount"] as? Number)?.toInt() ?: 0
        val tokensOut = (usage["candidatesTokenCount"] as? Number)?.toInt() ?: 0
        return tokensIn to tokensOut
    }
}
