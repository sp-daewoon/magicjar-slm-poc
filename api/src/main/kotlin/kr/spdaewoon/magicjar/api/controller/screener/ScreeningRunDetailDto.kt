package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

/**
 * screening 실행 단건 상세 DTO (ADR 0071 §6-3 + spec §6-3).
 *
 * GET /api/screener/runs/{id} response (옵션 영속 ON 시).
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "screening 실행 단건 상세 (screening_run + screening_match 배열)")
data class ScreeningRunDetailDto(
    @Schema(description = "screening 실행 ID", example = "1001")
    val id: Long,
    @Schema(description = "사용자 ID", example = "12345678")
    val userId: Long,
    @Schema(description = "전략 ID", example = "42")
    val strategyId: Long,
    @Schema(description = "universe 필터 해시", example = "abc12345")
    val universeFilterHash: String,
    @Schema(description = "DSL 해시", example = "def67890")
    val dslHash: String,
    @Schema(description = "시작 시각 (UTC)", example = "2026-05-14T10:00:00Z")
    val startedAt: Instant,
    @Schema(description = "완료 시각 (UTC)", example = "2026-05-14T10:00:05Z")
    val finishedAt: Instant?,
    @Schema(description = "universe 사이즈", example = "2847")
    val universeSize: Int,
    @Schema(description = "평가 완료 수", example = "2814")
    val evaluatedCount: Int,
    @Schema(description = "매칭 수", example = "23")
    val matchedCount: Int,
    @Schema(description = "skip 수", example = "33")
    val skippedCount: Int,
    @Schema(description = "상태 (RUNNING·COMPLETED·ERROR)", example = "COMPLETED")
    val status: String,
    @Schema(description = "마지막 오류 메시지 (정상 시 null)")
    val lastError: String?,
    @Schema(description = "트리거 출처 (API·CRON·MANUAL)", example = "API")
    val triggerSource: String,
    @Schema(description = "매칭 종목 상세 목록 (랭킹순)")
    val matches: List<MatchItem>,
) {
    /**
     * screening_match 개별 항목.
     */
    @Schema(description = "screening 매칭 종목 상세")
    data class MatchItem(
        @Schema(description = "매칭 ID", example = "5001")
        val id: Long,
        @Schema(description = "종목 코드", example = "005930")
        val instrumentCode: String,
        @Schema(description = "랭킹 (1-base)", example = "1")
        val rank: Int,
        @Schema(description = "랭킹 점수 (0~1 normalize)", example = "0.854321")
        val score: BigDecimal,
        @Schema(
            description = "점수 구성",
            example = """{"signal":0.40,"volume":0.30,"atr":0.20,"cap":0.10}""",
        )
        val scoreComponents: Map<String, Any>,
        @Schema(description = "BUY 시그널 사유", example = "RSI(14) < 30")
        val reason: String?,
    )
}
