package kr.spdaewoon.magicjar.consumer.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.ai.AiCommentProvider
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Claude CLI provider (차트해석/코멘트 — ADR 0026 §2 amend · ADR 0112 발굴 bridge 재사용).
 *
 * 사용자(최고 관리자) 지시 (2026-06-08): "종목상세 AI 차트해석을 우리 AI API랑 연동".
 * 종목발굴(ADR 0112)·프리셋 추천·전략 생성이 쓰는 Claude CLI bridge 를 코멘트 provider 로 추가한다.
 *
 * 활성 시점: `MAGICJAR_AI_ENABLED=true` + `MAGICJAR_AI_PROVIDER=claude-cli`.
 *
 * 키 불요 (구독 기반):
 * - api key 가 필요 없는 유일한 provider.
 * - `AiCommentDispatcherListener.shouldUseMockMode()` 가 claude-cli 를 "키 없음 = mock 강등" 으로 보내지 않도록
 *   별도 게이트 정정됨 (claude-cli 는 mockMode=auto 에서도 실해석 경로).
 *
 * 호출 경로 (api `ClaudeCliStrategyResearchAiTextClient` 패턴을 consumer 맥락으로 동등 구현):
 * - bridge-url 설정 시: POST `{bridge-url}/discover` `{prompt, timeoutSeconds}` → `{output, error}`
 *   (발굴 bridge 와 동일 계약 — 같은 bridge 서버 공유 가능. 모듈 경계상 api 클래스 import 불가 → 동등 구현.)
 * - bridge-url 미설정 시: 로컬 `claude -p <prompt>` ProcessBuilder 직접 실행.
 *
 * 비용/토큰:
 * - CLI 는 토큰 카운트 미제공 — tokensIn=0 / tokensOut=0 정직 기록.
 * - 구독 기반이라 별도 과금 0 — `AiCostCalculator` 가 claude-cli 를 $0 매핑.
 *
 * prompt_template 흐름 무변경:
 * - dispatcher 가 type=chart 등 5종 template 으로 최종 prompt 합성 후 그대로 전달.
 *   본 adapter 는 type 무지 — prompt 문자열만 받아 Claude 에 전달.
 *
 * Resilience4j:
 * - 본 adapter 는 raw call — Retry / CircuitBreaker 는 `AiCommentDispatcherListener` 가 합성.
 */
@Component
class ClaudeCliCommentAdapter(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.ai.claude-cli.command:claude}") private val command: String,
    @Value("\${magicjar.ai.claude-cli.bridge-url:}") private val bridgeUrl: String,
    @Value("\${magicjar.ai.claude-cli.timeout-seconds:300}") private val timeoutSeconds: Long,
) : AiCommentProvider {
    private val log = LoggerFactory.getLogger(ClaudeCliCommentAdapter::class.java)

    override val name: String = "claude-cli"
    override val model: String get() = if (bridgeUrl.isBlank()) "claude-cli" else "claude-cli-bridge"

    override fun comment(
        request: AiCommentRequest,
        prompt: String,
    ): AiCommentResponse {
        val started = System.currentTimeMillis()
        val content =
            if (bridgeUrl.isBlank()) {
                completeWithLocalCli(prompt)
            } else {
                completeWithBridge(prompt)
            }
        if (content.isBlank()) {
            throw IllegalStateException("Claude 응답이 비어 있습니다.")
        }
        val latencyMs = System.currentTimeMillis() - started

        log.debug(
            "[ClaudeCliAdapter] OK — requestId={} type={} via={} latencyMs={} contentLen={}",
            request.requestId,
            request.type,
            if (bridgeUrl.isBlank()) "cli" else "bridge",
            latencyMs,
            content.length,
        )

        return AiCommentResponse(
            requestId = request.requestId,
            provider = name,
            model = model,
            content = content.trim(),
            // CLI/bridge 는 토큰 카운트 미제공 — 0 정직 기록 (구독 기반 과금 0).
            tokensIn = 0,
            tokensOut = 0,
            latencyMs = latencyMs,
            cached = false,
            graceful = false,
        )
    }

    private fun completeWithBridge(prompt: String): String {
        val endpoint = URI.create("${bridgeUrl.trimEnd('/')}/discover")
        return try {
            val response =
                webClient
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ClaudeBridgeRequest(prompt = prompt, timeoutSeconds = timeoutSeconds))
                    .retrieve()
                    .bodyToMono(ClaudeBridgeResponse::class.java)
                    .block(Duration.ofSeconds(timeoutSeconds + 5))
                    ?: throw IllegalStateException("Claude 브릿지 응답이 없습니다.")
            response.output?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    response.error?.takeIf { it.isNotBlank() } ?: "Claude 브릿지 응답이 비어 있습니다.",
                )
        } catch (e: WebClientResponseException) {
            val bridgeMessage = bridgeErrorMessage(e.responseBodyAsString)
            throw IllegalStateException(
                bridgeMessage?.let { "Claude 브릿지 호출 실패: $it" } ?: "Claude 브릿지 호출 실패: ${e.statusCode}",
                e,
            )
        }
    }

    private fun completeWithLocalCli(prompt: String): String {
        val process =
            try {
                ProcessBuilder(command, "-p", prompt)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                throw IllegalStateException("Claude CLI를 실행할 수 없습니다: ${e.message}", e)
            }
        val outputFuture =
            CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Claude CLI 응답 시간이 초과되었습니다. timeout=${timeoutSeconds}s")
        }
        val output =
            try {
                outputFuture.get(2, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw IllegalStateException("Claude CLI 출력 수집 시간이 초과되었습니다.", e)
            }
        if (process.exitValue() != 0) {
            throw IllegalStateException("Claude CLI 실패(exit=${process.exitValue()}): ${output.take(300)}")
        }
        return output
    }

    private fun bridgeErrorMessage(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        return runCatching {
            objectMapper.readValue(responseBody, ClaudeBridgeResponse::class.java).error?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: responseBody.take(300)
    }

    private data class ClaudeBridgeRequest(
        val prompt: String,
        val timeoutSeconds: Long,
    )

    private data class ClaudeBridgeResponse(
        val output: String? = null,
        val error: String? = null,
    )
}
