package kr.spdaewoon.magicjar.consumer.ai

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kr.spdaewoon.magicjar.domain.ai.AiCommentRequest
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * ClaudeCliCommentAdapter (bridge 경로) 단위 테스트.
 *
 * 검증:
 *  (a) bridge output 정상 → content 매핑 + tokens 0 + provider/model claude-cli-bridge
 *  (b) bridge error 필드 → 예외 전파 (graceful 합성은 dispatcher 책임 — adapter 는 raw throw)
 *  (c) bridge output 공백 → 예외 (mock 합성 0 — adapter 는 mock 으로 강등하지 않음)
 *  (d) bridge HTTP 500 → 예외 전파
 *
 * CLI 직접 실행(ProcessBuilder) 경로는 환경 의존(claude 바이너리) → 통합 영역. 본 단위 테스트는 bridge 경로 한정.
 */
class ClaudeCliCommentAdapterTest {
    private val objectMapper = ObjectMapper()

    private fun adapterWithBridgeBody(
        status: HttpStatus,
        bodyJson: String,
    ): ClaudeCliCommentAdapter {
        val exchange =
            ExchangeFunction { _ ->
                Mono.just(
                    ClientResponse
                        .create(status)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(bodyJson)
                        .build(),
                )
            }
        val webClient = WebClient.builder().exchangeFunction(exchange).build()
        return ClaudeCliCommentAdapter(
            webClient = webClient,
            objectMapper = objectMapper,
            command = "claude",
            bridgeUrl = "http://host.docker.internal:8787",
            timeoutSeconds = 5,
        )
    }

    private fun request(): AiCommentRequest =
        AiCommentRequest(
            type = AiCommentType.CHART,
            context = mapOf("code" to "005930"),
            userId = 1L,
            requestId = UUID.randomUUID(),
        )

    @Test
    fun `(a) bridge output 정상 - content 매핑 + tokens 0`() {
        val adapter = adapterWithBridgeBody(HttpStatus.OK, """{"output":"삼성전자 일봉 상승 추세."}""")

        val response = adapter.comment(request(), "차트 해석 prompt")

        response.content shouldBe "삼성전자 일봉 상승 추세."
        response.provider shouldBe "claude-cli"
        response.model shouldBe "claude-cli-bridge"
        response.tokensIn shouldBe 0
        response.tokensOut shouldBe 0
        response.cached shouldBe false
        response.graceful shouldBe false
    }

    @Test
    fun `(b) bridge error 필드 - 예외 전파`() {
        val adapter = adapterWithBridgeBody(HttpStatus.OK, """{"error":"rate limit"}""")

        val ex = shouldThrow<IllegalStateException> { adapter.comment(request(), "p") }
        ex.message!! shouldContain "rate limit"
    }

    @Test
    fun `(c) bridge output 공백 - 예외 (mock 합성 0)`() {
        val adapter = adapterWithBridgeBody(HttpStatus.OK, """{"output":""}""")

        shouldThrow<IllegalStateException> { adapter.comment(request(), "p") }
    }

    @Test
    fun `(d) bridge HTTP 500 - 예외 전파`() {
        val adapter = adapterWithBridgeBody(HttpStatus.INTERNAL_SERVER_ERROR, """{"error":"boom"}""")

        val ex = shouldThrow<IllegalStateException> { adapter.comment(request(), "p") }
        ex.message!! shouldContain "Claude 브릿지 호출 실패"
    }
}
