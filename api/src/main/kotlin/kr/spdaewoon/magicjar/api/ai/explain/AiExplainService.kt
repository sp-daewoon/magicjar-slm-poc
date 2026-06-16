package kr.spdaewoon.magicjar.api.ai.explain

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.ai.AiCommentType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * AI 코멘트 요청 처리 application service (ADR 0026 §2-4 amend 정합).
 *
 * Controller 의 비즈니스 흐름을 한 클래스로 응집 — 4단계 가드 + prompt 합성 + Kafka publish.
 *
 * 4단계 가드 우선순위:
 * 1. KillSwitch / 전역 비활성 → `AiKillSwitchGuard`
 * 2. 1일 토큰 한도 → `AiTokenLimitGuard`
 * 3. prompt template 미존재 → `PromptTemplateResolver` throw `PromptTemplateNotFoundException`
 * 4. 정상 → prompt 변수 치환 + Kafka publish (consumer 가 LLM 호출)
 *
 * 모든 refuse 분기는 `AiCommentLogRecorder.recordRefusal()` 로 audit 기록 (graceful=TRUE).
 */
@Service
class AiExplainService(
    private val killSwitchGuard: AiKillSwitchGuard,
    private val tokenLimitGuard: AiTokenLimitGuard,
    private val promptResolver: PromptTemplateResolver,
    private val producer: AiCommentRequestKafkaProducer,
    private val recorder: AiCommentLogRecorder,
    private val objectMapper: ObjectMapper,
    private val chartContextEnricher: AiChartPromptContextEnricher,
) {
    private val log = LoggerFactory.getLogger(AiExplainService::class.java)

    fun handle(request: kr.spdaewoon.magicjar.api.ai.explain.AiExplainRequest): AiExplainResponse {
        val requestId = UUID.randomUUID()

        // 1단계 가드 — KillSwitch / 비활성
        killSwitchGuard.check()?.let { reason ->
            log.info(
                "[AiExplain] refuse — requestId={} userId={} type={} code={}",
                requestId,
                request.userId,
                request.type,
                reason.code,
            )
            recorder.recordRefusal(
                requestId = requestId,
                userId = request.userId,
                type = request.type,
                promptTemplate = "${request.type.toTemplateKey()}_unresolved",
                refuseCode = reason.code,
                userMessage = reason.userMessage,
            )
            return AiExplainResponse(
                requestId = requestId.toString(),
                status = "REFUSED",
                refuseCode = reason.code,
                userMessage = reason.userMessage,
            )
        }

        // 2단계 가드 — 1일 토큰 한도
        tokenLimitGuard.check(request.userId)?.let { reason ->
            log.info(
                "[AiExplain] refuse — requestId={} userId={} type={} code={} used={} limit={}",
                requestId,
                request.userId,
                request.type,
                reason.code,
                reason.used,
                reason.limit,
            )
            recorder.recordRefusal(
                requestId = requestId,
                userId = request.userId,
                type = request.type,
                promptTemplate = "${request.type.toTemplateKey()}_unresolved",
                refuseCode = reason.code,
                userMessage = reason.userMessage,
            )
            return AiExplainResponse(
                requestId = requestId.toString(),
                status = "REFUSED",
                refuseCode = reason.code,
                userMessage = reason.userMessage,
            )
        }

        val promptContext =
            if (request.type == AiCommentType.CHART) {
                chartContextEnricher.enrich(request.context)
            } else {
                request.context
            }

        // 3단계 — prompt 해석 (변수 치환)
        val resolved =
            try {
                promptResolver.resolve(request.type, promptContext)
            } catch (e: PromptTemplateNotFoundException) {
                log.warn("[AiExplain] prompt template not found — requestId={} type={} err={}", requestId, request.type, e.message)
                val refuseCode = "PROMPT_NOT_FOUND"
                val userMessage = "AI 응답을 준비하는 중 문제가 발생했습니다. 운영자에게 문의해 주세요."
                recorder.recordRefusal(
                    requestId = requestId,
                    userId = request.userId,
                    type = request.type,
                    promptTemplate = "${request.type.toTemplateKey()}_unresolved",
                    refuseCode = refuseCode,
                    userMessage = userMessage,
                )
                return AiExplainResponse(
                    requestId = requestId.toString(),
                    status = "REFUSED",
                    refuseCode = refuseCode,
                    userMessage = userMessage,
                )
            }

        // 4단계 — Kafka publish (consumer 가 LLM 호출 후 ai.comment.responded.v1 publish)
        val event =
            AiExplainRequestEvent(
                requestId = requestId,
                userId = request.userId,
                type = request.type.name,
                contextJson = objectMapper.writeValueAsString(promptContext),
                promptTemplateKey = resolved.templateKey,
                prompt = resolved.prompt,
            )
        producer.publish(event)
        log.info(
            "[AiExplain] accepted — requestId={} userId={} type={} template={}",
            requestId,
            request.userId,
            request.type,
            resolved.templateKey,
        )
        return AiExplainResponse(
            requestId = requestId.toString(),
            status = "ACCEPTED",
        )
    }
}
