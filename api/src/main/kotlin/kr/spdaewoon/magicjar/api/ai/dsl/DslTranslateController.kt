package kr.spdaewoon.magicjar.api.ai.dsl

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 자연어 → DSL 변환 REST endpoint.
 *
 * 정합:
 * - ADR 0026 (AI 옵션 — 사용자 트리거 한정)
 * - ADR 0023 (사용자 트리거 helper 정책)
 * - PR-35 spec §5-1 KIS 텍스트 1줄 표현식
 *
 * 사용자 트리거 한정 — 자동매매 본체는 본 endpoint를 호출하지 않습니다 (frontend Strategy Builder에서 사용자가 명시 호출).
 * 인증·권한은 W3 multi-tenant 진입 시점에 도입.
 *
 * `MAGICJAR_AI_ENABLED=false` 시 503 반환 — AI 기능 비활성 안내.
 */
@Tag(
    name = "AI / DSL Translate",
    description = "자연어 → KIS 호환 DSL 1줄 변환 (사용자 트리거 helper, 매매 결정권 0 — ADR 0026·0023)",
)
@RestController
@RequestMapping("/api/dsl")
class DslTranslateController(
    private val translator: NaturalLanguageDslTranslator,
    @Value("\${magicjar.ai.enabled:false}") private val aiEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(DslTranslateController::class.java)

    @Operation(
        summary = "자연어 → DSL 변환",
        description =
            "한국어 자연어로 묘사한 매매 조건을 KIS Strategy Builder 호환 DSL 한 줄 식으로 변환합니다. " +
                "예) '5일선이 20일선을 상향 돌파하고 거래량이 20일 평균의 1.5배 이상' → " +
                "'ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5'. " +
                "MAGICJAR_AI_ENABLED=false 시 503. ANTHROPIC_API_KEY 미설정 + MAGICJAR_AI_MOCK_MODE=auto 시 룰 기반 mock 변환. " +
                "변환 결과는 사용자가 검토 후 명시적으로 등록할 때만 적용됩니다 (자동 등록 0). " +
                "untranslatable=true 시 dsl=null + reasoning에 사유 — 사용자에게 자연어 재입력 안내. " +
                "결과 DSL은 등록 시 KisYamlDslParser.validateStatic() (depth ≤ 32 · 노드 ≤ 10000 · indicator 호출 ≤ 50) 통과 의무.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변환 성공 또는 graceful untranslatable 응답"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 입력 — 빈 자연어 · 1000자 초과",
            content = [Content(schema = Schema(implementation = ErrorBody::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "AI 비활성 — MAGICJAR_AI_ENABLED=false",
            content = [Content(schema = Schema(implementation = ErrorBody::class))],
        ),
    )
    @PostMapping("/translate-from-natural")
    fun translate(
        @RequestBody @Valid request: DslTranslateRequest,
    ): ResponseEntity<Any> {
        if (!aiEnabled) {
            log.info("DSL 자연어 변환 호출 거부 — MAGICJAR_AI_ENABLED=false")
            return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                    ErrorBody(
                        error = "AI_DISABLED",
                        message = "AI 기능이 비활성 상태입니다. .env에서 MAGICJAR_AI_ENABLED=true로 변경 후 재기동하세요.",
                    ),
                )
        }
        val result = translator.translate(request.naturalLanguage)
        return ResponseEntity.ok(result.toResponse())
    }
}

@Schema(description = "에러 응답 본문")
data class ErrorBody(
    @field:Schema(description = "에러 코드", example = "AI_DISABLED")
    val error: String,
    @field:Schema(description = "사용자 안내 메시지(한국어)")
    val message: String,
)
