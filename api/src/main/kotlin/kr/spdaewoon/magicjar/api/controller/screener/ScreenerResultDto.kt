package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema

/**
 * screening 결과 + 랭킹 응답 DTO (ADR 0071 §6-1 + spec §6-1).
 *
 * POST /api/screener/evaluate response body.
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "screening 결과 + 랭킹")
data class ScreenerResultDto(
    @Schema(description = "screening 캐시 키 (debug용)", example = "screener:result:dsl:abc123:universe:xyz789")
    val cacheKey: String,
    @Schema(description = "universe 사이즈 (필터 후 종목 수)", example = "2847")
    val universeSize: Int,
    @Schema(description = "평가 완료 수 (skip 제외)", example = "2814")
    val evaluatedCount: Int,
    @Schema(description = "BUY 시그널 매칭 종목 수", example = "23")
    val matchedCount: Int,
    @Schema(description = "skip 사유별 카운트", example = """{"캔들 미수집": 12, "캔들 부족": 21}""")
    val skippedReasons: Map<String, Int>,
    @Schema(description = "랭킹된 매칭 결과 (limit 적용, descending score)")
    val matches: List<ScreenerMatchDto>,
    @Schema(description = "전체 평가 elapsed (ms)", example = "4521")
    val elapsedMs: Long,
    @Schema(description = "캐시 HIT 여부 (true=Redis cache hit)", example = "false")
    val cacheHit: Boolean,
)
