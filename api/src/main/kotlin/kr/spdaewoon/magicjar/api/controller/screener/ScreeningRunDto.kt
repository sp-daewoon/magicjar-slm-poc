package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * screening 실행 이력 목록 항목 DTO (ADR 0071 §6-2 + spec §6-2).
 *
 * GET /api/screener/runs response item (옵션 영속 ON 시).
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "screening 실행 이력 항목")
data class ScreeningRunDto(
    @Schema(description = "screening 실행 ID", example = "1001")
    val id: Long,
    @Schema(description = "사용자 ID", example = "12345678")
    val userId: Long,
    @Schema(description = "전략 ID", example = "42")
    val strategyId: Long,
    @Schema(description = "universe 필터 해시 (SHA-256)", example = "abc12345")
    val universeFilterHash: String,
    @Schema(description = "DSL 해시 (SHA-256)", example = "def67890")
    val dslHash: String,
    @Schema(description = "시작 시각 (UTC)", example = "2026-05-14T10:00:00Z")
    val startedAt: Instant,
    @Schema(description = "완료 시각 (UTC, null=진행 중)", example = "2026-05-14T10:00:05Z")
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
    @Schema(description = "마지막 오류 메시지 (정상 시 null)", example = "null")
    val lastError: String?,
    @Schema(description = "트리거 출처 (API·CRON·MANUAL)", example = "API")
    val triggerSource: String,
)
