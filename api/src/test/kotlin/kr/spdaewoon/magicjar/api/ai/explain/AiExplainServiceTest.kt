package kr.spdaewoon.magicjar.api.ai.explain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.junit.jupiter.api.Test

/**
 * AiExplainService — 4단계 가드 통합 + Kafka publish 정상 path 검증.
 *
 * 가드 우선순위 LOCK:
 * 1. KillSwitch (ADR 0094 §4-4-B amend)
 * 2. 1일 토큰 한도 (ADR 0026 §2-5 amend)
 * 3. prompt template 미존재
 * 4. 정상 publish
 */
class AiExplainServiceTest {
    private val killSwitch = mockk<AiKillSwitchGuard>()
    private val tokenLimit = mockk<AiTokenLimitGuard>()
    private val resolver = mockk<PromptTemplateResolver>()
    private val producer = mockk<AiCommentRequestKafkaProducer>(relaxed = true)
    private val recorder = mockk<AiCommentLogRecorder>(relaxed = true)
    private val chartContextEnricher = mockk<AiChartPromptContextEnricher>()
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val service = AiExplainService(killSwitch, tokenLimit, resolver, producer, recorder, objectMapper, chartContextEnricher)

    private val baseRequest =
        AiExplainRequest(
            type = AiCommentType.MARKET,
            userId = 1L,
            context = mapOf("code" to "005930"),
        )

    @Test
    fun `1단계 KillSwitch refuse - Kafka publish 0건 + audit 1건`() {
        every { killSwitch.check() } returns
            AiKillSwitchGuard.RefuseReason(
                code = "KILLSWITCH_ENGAGED",
                userMessage = "AI 차단됨",
            )

        val response = service.handle(baseRequest)
        response.status shouldBe "REFUSED"
        response.refuseCode shouldBe "KILLSWITCH_ENGAGED"
        response.userMessage shouldBe "AI 차단됨"

        verify(exactly = 1) { recorder.recordRefusal(any(), any(), any(), any(), "KILLSWITCH_ENGAGED", any()) }
        verify(exactly = 0) { producer.publish(any()) }
    }

    @Test
    fun `2단계 토큰 한도 refuse - Kafka publish 0건`() {
        every { killSwitch.check() } returns null
        every { tokenLimit.check(any()) } returns
            AiTokenLimitGuard.RefuseReason(
                code = "DAILY_TOKEN_LIMIT_EXCEEDED",
                userMessage = "초과",
                used = 1500,
                limit = 1000,
            )

        val response = service.handle(baseRequest)
        response.status shouldBe "REFUSED"
        response.refuseCode shouldBe "DAILY_TOKEN_LIMIT_EXCEEDED"

        verify(exactly = 1) { recorder.recordRefusal(any(), any(), any(), any(), "DAILY_TOKEN_LIMIT_EXCEEDED", any()) }
        verify(exactly = 0) { producer.publish(any()) }
    }

    @Test
    fun `3단계 prompt 미존재 refuse - Kafka publish 0건`() {
        every { killSwitch.check() } returns null
        every { tokenLimit.check(any()) } returns null
        every { resolver.resolve(any(), any()) } throws PromptTemplateNotFoundException("market")

        val response = service.handle(baseRequest)
        response.status shouldBe "REFUSED"
        response.refuseCode shouldBe "PROMPT_NOT_FOUND"

        verify(exactly = 1) { recorder.recordRefusal(any(), any(), any(), any(), "PROMPT_NOT_FOUND", any()) }
        verify(exactly = 0) { producer.publish(any()) }
    }

    @Test
    fun `4단계 정상 - Kafka publish 1건 + audit 0건 (성공 audit는 consumer side)`() {
        every { killSwitch.check() } returns null
        every { tokenLimit.check(any()) } returns null
        every { resolver.resolve(any(), any()) } returns
            PromptTemplateResolver.ResolvedPrompt(
                prompt = "final prompt",
                templateKey = "market_v1",
                version = "v1",
            )
        every { producer.publish(any()) } just Runs

        val response = service.handle(baseRequest)
        response.status shouldBe "ACCEPTED"
        response.refuseCode shouldBe null
        response.userMessage shouldBe null

        verify(exactly = 1) { producer.publish(any()) }
        // 정상 분기 — recorder refusal 0건 (audit 는 ai.comment.responded.v1 consume 시점)
        verify(exactly = 0) { recorder.recordRefusal(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `CHART 정상 - 선택 timeframe으로 실측 차트 context 보강 후 prompt resolve`() {
        val request =
            AiExplainRequest(
                type = AiCommentType.CHART,
                userId = 1L,
                context = mapOf("code" to "005930", "timeframe" to "1d"),
            )
        val enriched =
            mapOf(
                "code" to "005930",
                "timeframe" to "1d(일봉)",
                "indicators" to "RSI14=55.2",
                "priceFlow" to "last20=3.4%",
            )
        every { killSwitch.check() } returns null
        every { tokenLimit.check(any()) } returns null
        every { chartContextEnricher.enrich(request.context) } returns enriched
        every { resolver.resolve(AiCommentType.CHART, enriched) } returns
            PromptTemplateResolver.ResolvedPrompt(
                prompt = "chart final prompt",
                templateKey = "chart_v2",
                version = "v2",
            )
        every { producer.publish(any()) } just Runs

        val response = service.handle(request)
        response.status shouldBe "ACCEPTED"

        verify(exactly = 1) { chartContextEnricher.enrich(request.context) }
        verify(exactly = 1) { resolver.resolve(AiCommentType.CHART, enriched) }
        verify(exactly = 1) {
            producer.publish(
                match {
                    it.prompt == "chart final prompt" &&
                        it.promptTemplateKey == "chart_v2" &&
                        it.contextJson.contains("\"priceFlow\":\"last20=3.4%\"")
                },
            )
        }
    }
}
