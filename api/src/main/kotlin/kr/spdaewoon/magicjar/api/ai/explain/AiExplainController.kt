package kr.spdaewoon.magicjar.api.ai.explain

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AI 코멘트 진입점 REST controller (ADR 0026 §2-4 amend · W4 R1 T-AI Phase 1).
 *
 * 단일 endpoint — POST `/api/ai/explain` 으로 5 진입점 모두 처리 (type 으로 분기).
 *
 * 실제 LLM 호출은 비동기 (consumer `AiCommentDispatcherListener`) — REST 응답은 즉시 (~10ms) `ACCEPTED` + requestId.
 * 사용자는 STOMP `/topic/ai.explain.{requestId}` 구독해 실제 응답 수신.
 *
 * Refuse 분기 (4단계 가드):
 * - KillSwitch 활성 → `KILLSWITCH_ENGAGED`
 * - AI 전역 비활성 → `AI_DISABLED`
 * - 1일 토큰 한도 초과 → `DAILY_TOKEN_LIMIT_EXCEEDED`
 * - prompt template 미존재 → `PROMPT_NOT_FOUND`
 *
 * 모든 refuse 는 HTTP 200 + status=REFUSED 응답 (HTTP 503/429 회피 — frontend AiCard 가 일관 처리).
 */
@RestController
@RequestMapping("/api/ai")
@Tag(
    name = "ai-explain",
    description = "AI 코멘트 진입점 — 사용자 명시 클릭 trigger 한정 · 5 진입점 (SYMBOL/CHART/STRATEGY/SIGNAL/MARKET) advisory only · 매매 결정권 0",
)
class AiExplainController(
    private val service: AiExplainService,
) {
    @PostMapping("/explain")
    @Operation(
        summary = "AI 코멘트 요청 (5 진입점 공통 — 비동기)",
        description = """
            사용자가 UI 의 5 진입점 ("AI 종목 분석" · "차트 해석" · "전략 평가" · "이 시그널 해석" · "시장 추세 해석") 중 하나를
            클릭할 때 호출하는 endpoint.

            응답은 즉시 (~10ms) `ACCEPTED` 또는 `REFUSED` 를 반환하며, 실제 LLM 응답은 비동기로
            STOMP `/topic/ai.explain.{requestId}` 채널로 fanout 됩니다. frontend AiCard 가 구독해
            응답을 렌더링합니다.

            4단계 가드:
            1. KillSwitch (`MAGICJAR_AI_KILLSWITCH=true` 시 모든 호출 차단)
            2. AI 전역 비활성 (`MAGICJAR_AI_ENABLED=false` 가 default — 사용자 명시 활성 전 차단)
            3. 1일 사용자 토큰 한도 (default 1000 — `magicjar.ai.daily-token-limit` env override)
            4. prompt template 존재 여부 (V50 seed `is_active=TRUE` 행)

            매매 결정권 0 — 본 endpoint 는 advisory only. 호출 결과로 자동 매수·매도 흐름은 0건입니다.
        """,
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "요청 접수 또는 graceful refuse — status 필드로 분기 (ACCEPTED / REFUSED)",
            content = [Content(schema = Schema(implementation = AiExplainResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 (type 누락 / userId 비양수 / context 누락) — Bean Validation 한국어 메시지",
        ),
    )
    fun explain(
        @Parameter(description = "AI 요청 본문 — type · userId · context (variables_schema 정합)", required = true)
        @Valid
        @RequestBody
        request: AiExplainRequest,
    ): ResponseEntity<AiExplainResponse> = ResponseEntity.ok(service.handle(request))
}
