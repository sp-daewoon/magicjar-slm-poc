package kr.spdaewoon.magicjar.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 패턴 인식 REST 응답 DTO (spec §6-4 LOCK).
 *
 * BigDecimal·Instant를 String으로 직렬화 — JSON 정밀도 보존 + frontend 파싱 단순화 (T-IND 정합).
 * NEUTRAL direction은 matches 미포함 (KIS L52 graceful 정합).
 * 룰 5 — LLM provider import 0건.
 */
@Schema(description = "패턴 인식 결과 응답")
data class PatternResponseDto(
    @Schema(description = "인식된 패턴 매칭 목록 (direction=NEUTRAL 또는 미감지는 미포함)")
    val matches: List<PatternMatchDto>,
) {
    @Schema(description = "패턴 인식 단건 결과")
    data class PatternMatchDto(
        @Schema(description = "패턴 catalog id", example = "hammer")
        val pattern: String,
        @Schema(description = "패턴 시작 캔들 시각 (ISO 8601 UTC)", example = "2024-01-02T00:00:00Z")
        val startTime: String,
        @Schema(description = "패턴 종료 캔들 시각 (ISO 8601 UTC)", example = "2024-01-04T00:00:00Z")
        val endTime: String,
        @Schema(description = "패턴 방향성 (BULL·BEAR)", example = "BULL")
        val direction: String,
        @Schema(description = "인식 신뢰도 (1.0 = 확정, post-MVP 그라데이션 예정)", example = "1.0")
        val confidence: String,
        @Schema(description = "detector 내부 파라미터 (optional — R3 overlay 표시용)")
        val params: Map<String, String>? = null,
    )
}
