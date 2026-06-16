package kr.spdaewoon.magicjar.consumer.ai

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.kotlin.circuitbreaker.executeFunction
import io.github.resilience4j.kotlin.retry.executeFunction
import io.github.resilience4j.retry.Retry
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisAiPromptCacheAdapter
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * AI 코멘트 요청 dispatcher (ADR 0026 §2-4 amend · W4 R1 T-AI Phase 1).
 *
 * 흐름:
 * 1. Kafka `ai.comment.requested.v1` consume
 * 2. Redis prompt cache 조회 — hit 시 LLM 호출 0건 / cached=TRUE 응답 publish
 * 3. miss 시 mock mode 분기 결정 (`AiProviderRegistry.resolve()` or `MockCommentAdapter`)
 * 4. Resilience4j Retry 3회 (1s/2s/4s) + CircuitBreaker 30s 50% threshold 로 adapter 호출
 * 5. 성공 → cache PUT + Kafka `ai.comment.responded.v1` publish
 * 6. 실패 (circuit OPEN / 모든 retry fail) → graceful refuse 응답 publish
 *
 * 본 dispatcher 가 5 진입점 type 매핑 단일 책임 — adapter 는 type 무지 (prompt 만 받음).
 *
 * 룰 5 정합:
 * - W1·W2·W3 영역 import 0건 — strategy/signal/order/risk 흐름 0 touch
 * - 매매 결정권 0 (ADR 0023 정합) — 본 listener 는 STOMP fanout 만 trigger
 */
@Component
class AiCommentDispatcherListener(
    private val registry: AiProviderRegistry,
    private val mockAdapter: MockCommentAdapter,
    private val cache: RedisAiPromptCacheAdapter,
    private val producer: AiCommentResponseKafkaProducer,
    private val costCalculator: AiCostCalculator,
    private val retry: Retry,
    private val circuitBreaker: CircuitBreaker,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.ai.mock-mode:auto}") private val mockMode: String,
    @Value("\${spring.ai.anthropic.api-key:}") private val anthropicApiKey: String,
    @Value("\${magicjar.ai.gemini.api-key:}") private val geminiApiKey: String,
    @Value("\${magicjar.ai.openai.api-key:}") private val openAiApiKey: String,
    @Value("\${magicjar.ai.provider:anthropic}") private val configuredProvider: String,
) {
    private val log = LoggerFactory.getLogger(AiCommentDispatcherListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.Ai.COMMENT_REQUESTED],
        groupId = "magicjar-consumer-ai-dispatcher",
    )
    fun onRequest(record: ConsumerRecord<String, String>) {
        val envelope =
            try {
                objectMapper.readValue(record.value(), AiRequestEnvelope::class.java)
            } catch (e: Exception) {
                log.error("[AiDispatcher] payload parse 실패 — offset={} err={}", record.offset(), e.message)
                return
            }

        val request =
            AiCommentRequest(
                type = AiCommentType.valueOf(envelope.type),
                context = parseContext(envelope.contextJson),
                userId = envelope.userId,
                requestId = envelope.requestId,
            )

        // 1. 캐시 조회
        val cached = cache.get(envelope.prompt, request.requestId)
        if (cached != null) {
            log.info(
                "[AiDispatcher] cache hit — requestId={} type={} template={}",
                request.requestId,
                request.type,
                envelope.promptTemplateKey,
            )
            publishResponse(envelope, cached, BigDecimal.ZERO)
            return
        }

        // 2. mock 분기 결정
        val resolvedAdapter =
            if (shouldUseMockMode()) {
                mockAdapter
            } else {
                registry.resolve()
            }

        // 3. Resilience4j 통합 호출
        val response =
            try {
                callWithResilience(resolvedAdapter, request, envelope.prompt)
            } catch (e: Exception) {
                log.warn(
                    "[AiDispatcher] LLM 호출 최종 실패 — requestId={} adapter={} err={}",
                    request.requestId,
                    resolvedAdapter.name,
                    e.message,
                )
                val gracefulRefuse =
                    AiCommentResponse(
                        requestId = request.requestId,
                        provider = resolvedAdapter.name,
                        model = resolvedAdapter.model,
                        content = "AI 응답을 가져오는 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                        tokensIn = 0,
                        tokensOut = 0,
                        latencyMs = 0L,
                        cached = false,
                        graceful = true,
                    )
                publishResponse(envelope, gracefulRefuse, BigDecimal.ZERO, errorMessage = e.message)
                return
            }

        // 4. 정상 응답 → cache PUT + publish
        if (!response.graceful && !response.cached) {
            cache.put(envelope.prompt, response)
        }
        val cost = costCalculator.compute(response.provider, response.model, response.tokensIn, response.tokensOut)
        publishResponse(envelope, response, cost)
    }

    private fun callWithResilience(
        adapter: kr.spdaewoon.magicjar.domain.ai.AiCommentProvider,
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse {
        // CircuitBreaker → Retry 순으로 감싸기 (외부 = CB / 내부 = Retry — 표준 권장 구성)
        return circuitBreaker.executeFunction {
            retry.executeFunction {
                adapter.comment(request, prompt)
            }
        }
    }

    /**
     * mock mode 판정 (ADR 0026 §2-1 amend):
     * - mockMode=mock → 강제 mock
     * - mockMode=real → mock 0 (key 미설정 시 LLM 실패 path)
     * - mockMode=auto → 설정 provider 의 key 미설정 시 mock
     *
     * claude-cli (ADR 0112 발굴 bridge 재사용 — 2026-06-08 종목상세 차트해석 연동):
     * - API key 불요 (구독 기반 Claude CLI/bridge) → "키 없음 = mock 강등" 로직에서 제외.
     * - auto 모드에서도 claude-cli 는 항상 실해석 경로 (mock 0). bridge-url/CLI 가용성은 adapter 가 판단.
     */
    private fun shouldUseMockMode(): Boolean =
        decideMockMode(
            mockMode = mockMode,
            provider = configuredProvider,
            anthropicKeyBlank = anthropicApiKey.isBlank(),
            geminiKeyBlank = geminiApiKey.isBlank(),
            openAiKeyBlank = openAiApiKey.isBlank(),
        )

    private fun publishResponse(
        envelope: AiRequestEnvelope,
        response: AiCommentResponse,
        costUsd: BigDecimal,
        errorMessage: String? = null,
    ) {
        val out =
            AiResponseEnvelope(
                requestId = response.requestId,
                userId = envelope.userId,
                commentType = envelope.type,
                provider = response.provider,
                model = response.model,
                promptTemplate = envelope.promptTemplateKey,
                content = response.content,
                tokensIn = response.tokensIn,
                tokensOut = response.tokensOut,
                latencyMs = response.latencyMs,
                costUsd = costUsd.toPlainString(),
                cached = response.cached,
                graceful = response.graceful,
                errorMessage = errorMessage,
            )
        producer.publish(out)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseContext(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            log.warn("[AiDispatcher] contextJson parse 실패 — err={}", e.message)
            emptyMap()
        }
    }
}

/**
 * mock mode 판정 순수 함수 (테스트 가능 — `AiCommentDispatcherListener.shouldUseMockMode()` 위임 대상).
 *
 * - mockMode=mock → 강제 mock
 * - mockMode=real → mock 0 (key 미설정 시 LLM 실패 path)
 * - mockMode=auto → 설정 provider 의 key 미설정 시 mock.
 *   단 claude-cli 는 키 불요(구독 기반) → auto 에서도 mock 0 (실해석 경로).
 */
internal fun decideMockMode(
    mockMode: String,
    provider: String,
    anthropicKeyBlank: Boolean,
    geminiKeyBlank: Boolean,
    openAiKeyBlank: Boolean,
): Boolean =
    when (mockMode.lowercase()) {
        "mock" -> true
        "real" -> false
        "auto" ->
            when (provider.lowercase()) {
                "anthropic" -> anthropicKeyBlank
                "gemini" -> geminiKeyBlank
                "openai" -> openAiKeyBlank
                // claude-cli 는 키 불요 — 키 미설정을 mock 트리거로 보지 않는다.
                "claude-cli" -> false
                else -> true
            }
        else -> true
    }

/**
 * Kafka `ai.comment.requested.v1` payload v1 (consumer-local schema — api 의 `AiExplainRequestEvent` 와 1:1).
 */
data class AiRequestEnvelope(
    val eventId: String,
    val requestId: UUID,
    val userId: Long,
    val type: String,
    val contextJson: String,
    val promptTemplateKey: String,
    val prompt: String,
    val occurredAt: String,
    val schemaVersion: Int = 1,
)
