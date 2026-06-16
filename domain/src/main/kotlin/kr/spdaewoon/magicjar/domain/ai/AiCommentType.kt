package kr.spdaewoon.magicjar.domain.ai

/**
 * AI 코멘트 진입점 타입 (ADR 0026 §2-2 amend — 4 → 5 진입점 LOCK).
 *
 * 모든 type 은 **사용자 명시 클릭 trigger only** — 자동 호출 0건.
 * 매매 결정권 0 (ADR 0023 정합) — 본 enum 값이 SignalEngine / OrderDispatcher 흐름에
 * 진입할 경우 컴파일 시점에서 거부되어야 합니다 (W1·W2·W3 영역 LLM provider import 0건 grep 검증).
 *
 * | enum | 진입점 (frontend route) | prompt_template (V50 seed) |
 * |---|---|---|
 * | `SYMBOL`   | `/symbol/:code` 종목 상세 "AI 종목 분석"        | `prompt_template:symbol_v1`   |
 * | `CHART`    | `/symbol/:code` 차트 "차트 해석"                 | `prompt_template:chart_v1`    |
 * | `STRATEGY` | `/strategy/:id` 전략 "전략 평가"                 | `prompt_template:strategy_v1` |
 * | `SIGNAL`   | `/dashboard` 시그널 카드 "이 시그널 해석"        | `prompt_template:signal_v1`   |
 * | `MARKET`   | `/market/trend` 시장 추세 "시장 추세 해석" (2026-05-24 amend — ADR 0100·0101 정합) | `prompt_template:market_v1` |
 */
enum class AiCommentType {
    SYMBOL,
    CHART,
    STRATEGY,
    SIGNAL,
    MARKET,

    ;

    /**
     * V50 seed `prompt_template.type` 컬럼 값과 1:1 매핑.
     * lowercase + suffix `_v1` — 향후 prompt 버전 업 시 PromptTemplateResolver 가 latest active 조회.
     */
    fun toTemplateKey(): String = name.lowercase()
}
