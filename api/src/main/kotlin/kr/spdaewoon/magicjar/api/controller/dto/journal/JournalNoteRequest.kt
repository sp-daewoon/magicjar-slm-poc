package kr.spdaewoon.magicjar.api.controller.dto.journal

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 매매일지 메모 작성 요청 DTO — ADR 0072 §6-3 Swagger 4단.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "매매일지 메모 작성 요청")
data class JournalNoteRequest(
    @Schema(description = "사용자 ID (multi-tenant 격리)", example = "12345678")
    @field:NotNull
    val userId: Long?,
    @Schema(description = "매매일지 entry ID", example = "1001")
    @field:NotNull
    val journalEntryId: Long?,
    @Schema(
        description = "메모 본문 (free-form, 최대 4000자)",
        example = "이번 매매는 RSI 과매도 진입 + MACD bullish cross 신호로 진입. 익절 룰 7%까지 유지 검토.",
    )
    @field:NotBlank
    @field:Size(max = 4000)
    val noteText: String?,
)
