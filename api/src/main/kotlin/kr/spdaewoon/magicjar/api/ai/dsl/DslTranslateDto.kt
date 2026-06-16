package kr.spdaewoon.magicjar.api.ai.dsl

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 자연어 → DSL 변환 REST DTO.
 *
 * 정합:
 * - PR-35 spec §5-1 (KIS 텍스트 1줄 표현식)
 * - 사용자 메모리 `feedback_swagger_pattern` (한국어 설명 + example 의무)
 * - ADR 0026 사용자 트리거 helper
 */
@Schema(description = "자연어 → DSL 변환 요청")
data class DslTranslateRequest(
    @field:Schema(
        description =
            "한국어 자연어 표현 (지표·기간·임계값 명시 권장). 변환 결과는 KIS 호환 1줄 DSL. " +
                "예: 'RSI 14가 30 미만일 때' → 'rsi(14) < 30'.",
        example = "5일 이동평균이 20일 이동평균을 상향 돌파하고 거래량이 20일 평균의 1.5배 이상",
        requiredMode = Schema.RequiredMode.REQUIRED,
        maxLength = 1000,
    )
    @field:NotBlank(message = "자연어 입력은 비어 있을 수 없습니다.")
    @field:Size(max = 1000, message = "자연어 입력은 1000자 이하여야 합니다.")
    val naturalLanguage: String,
)

@Schema(description = "자연어 → DSL 변환 응답 — `untranslatable=true` 시 dsl=null + reasoning 메시지로 사용자 안내")
data class DslTranslateResponse(
    @field:Schema(description = "변환된 KIS 호환 DSL 한 줄 표현식 (변환 불가 시 null)", example = "rsi(14) < 30", nullable = true)
    val dsl: String?,
    @field:Schema(description = "변환 불가 여부 (true 시 dsl=null, reasoning에 안내 메시지)", example = "false")
    val untranslatable: Boolean,
    @field:Schema(
        description = "변환 모드 — REAL (Anthropic 실호출) · MOCK (룰 fallback, key 미설정 또는 mock 강제) · DISABLED (AI 비활성)",
        example = "MOCK",
    )
    val mode: String,
    @field:Schema(
        description = "추론·실패 사유 (사용자 안내용 한국어). 변환 결과를 사용자에게 보일 때 함께 표시 권장.",
        example = "Mock 룰 기반 변환 결과입니다 (ANTHROPIC_API_KEY 미설정 또는 mock 강제 모드).",
    )
    val reasoning: String,
)

internal fun DslTranslateResult.toResponse(): DslTranslateResponse =
    DslTranslateResponse(
        dsl = dsl,
        untranslatable = untranslatable,
        mode = mode.name,
        reasoning = reasoning,
    )
