package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class StrategyResearchAiTextResult(
    val provider: String,
    val text: String,
)

fun interface StrategyResearchAiTextClient {
    fun complete(prompt: String): StrategyResearchAiTextResult
}

@Component
class ClaudeCliStrategyResearchAiTextClient(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.strategy-research.ai-discovery.enabled:true}") private val enabled: Boolean,
    @Value("\${magicjar.strategy-research.ai-discovery.command:claude}") private val command: String,
    @Value("\${magicjar.strategy-research.ai-discovery.bridge-url:}") private val bridgeUrl: String,
    @Value("\${magicjar.strategy-research.ai-discovery.timeout-seconds:300}") private val timeoutSeconds: Long,
) : StrategyResearchAiTextClient {
    override fun complete(prompt: String): StrategyResearchAiTextResult {
        if (!enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 종목발굴이 비활성화되어 있습니다.")
        }
        val text =
            if (bridgeUrl.isBlank()) {
                completeWithLocalCli(prompt)
            } else {
                completeWithBridge(prompt)
            }
        if (text.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 응답이 비어 있습니다.")
        }
        val provider = if (bridgeUrl.isBlank()) "claude-cli" else "claude-cli-bridge"
        return StrategyResearchAiTextResult(provider = provider, text = text)
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
                    ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 브릿지 응답이 없습니다.")
            response.output?.takeIf { it.isNotBlank() }
                ?: throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    response.error?.takeIf { it.isNotBlank() } ?: "Claude 브릿지 응답이 비어 있습니다.",
                )
        } catch (e: WebClientResponseException) {
            val bridgeMessage = bridgeErrorMessage(e.responseBodyAsString)
            val status =
                when (e.statusCode.value()) {
                    429 -> HttpStatus.TOO_MANY_REQUESTS
                    504 -> HttpStatus.GATEWAY_TIMEOUT
                    else -> HttpStatus.SERVICE_UNAVAILABLE
                }
            throw ResponseStatusException(
                status,
                bridgeMessage?.let { "Claude 브릿지 호출 실패: $it" } ?: "Claude 브릿지 호출 실패: ${e.statusCode}",
                e,
            )
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude 브릿지에 연결할 수 없습니다: ${e.message}", e)
        }
    }

    private fun completeWithLocalCli(prompt: String): String {
        val process =
            try {
                ProcessBuilder(command, "-p", prompt)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude CLI를 실행할 수 없습니다: ${e.message}", e)
            }
        val outputFuture =
            CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Claude CLI 응답 시간이 초과되었습니다. timeout=${timeoutSeconds}s")
        }
        val output =
            try {
                outputFuture.get(2, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude CLI 출력 수집 시간이 초과되었습니다.", e)
            }
        if (process.exitValue() != 0) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Claude CLI 실패(exit=${process.exitValue()}): ${output.take(300)}")
        }
        return output
    }

    private data class ClaudeBridgeRequest(
        val prompt: String,
        val timeoutSeconds: Long,
    )

    private data class ClaudeBridgeResponse(
        val output: String? = null,
        val error: String? = null,
    )

    private fun bridgeErrorMessage(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        return runCatching {
            objectMapper.readValue(responseBody, ClaudeBridgeResponse::class.java).error?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: responseBody.take(300)
    }
}
