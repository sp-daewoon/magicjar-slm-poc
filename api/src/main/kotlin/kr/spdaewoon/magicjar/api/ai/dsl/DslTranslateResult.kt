package kr.spdaewoon.magicjar.api.ai.dsl

/**
 * 자연어 → DSL 변환 결과 도메인.
 *
 * 정합:
 * - ADR 0026 (AI 옵션 — 사용자 트리거 한정, 매매 결정권 0)
 * - PR-35 spec §5-1 KIS 텍스트 1줄 표현식 LOCK
 * - 사용자 메모리 `project_system_vision.md` — AI는 분석 보조, 매매는 룰/시그널
 *
 * 출력 mode 분기:
 * - REAL — Anthropic API 실제 호출 결과
 * - MOCK — `MAGICJAR_AI_MOCK_MODE=mock` 또는 `auto + key 없음`일 때 룰 기반 fallback
 * - DISABLED — `MAGICJAR_AI_ENABLED=false` 시 endpoint 호출 차단(503) 보조 표지자
 *
 * `dsl == null` && `untranslatable == true` 시 사용자에게 자연어 재입력 안내.
 */
data class DslTranslateResult(
    val dsl: String?,
    val untranslatable: Boolean,
    val mode: TranslateMode,
    val reasoning: String,
    val rawResponse: String? = null,
) {
    enum class TranslateMode { REAL, MOCK, DISABLED }

    companion object {
        fun untranslatable(
            mode: TranslateMode,
            reasoning: String,
            raw: String? = null,
        ): DslTranslateResult =
            DslTranslateResult(
                dsl = null,
                untranslatable = true,
                mode = mode,
                reasoning = reasoning,
                rawResponse = raw,
            )

        fun success(
            dsl: String,
            mode: TranslateMode,
            reasoning: String,
            raw: String? = null,
        ): DslTranslateResult =
            DslTranslateResult(
                dsl = dsl,
                untranslatable = false,
                mode = mode,
                reasoning = reasoning,
                rawResponse = raw,
            )
    }
}
