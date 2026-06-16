package kr.spdaewoon.magicjar.api.controller.dto.journal

import io.swagger.v3.oas.annotations.media.Schema
import java.time.OffsetDateTime

/**
 * 매매일지 사용자 메모 DTO — ADR 0072 §6-3.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "매매일지 사용자 메모")
data class JournalNoteDto(
    @Schema(description = "메모 ID", example = "501")
    val id: Long,
    @Schema(description = "메모 본문")
    val noteText: String,
    @Schema(description = "작성 시각 (KST)", example = "2026-05-14T18:45:12+09:00")
    val createdAt: OffsetDateTime,
    @Schema(description = "갱신 시각 (KST)", example = "2026-05-14T18:45:12+09:00")
    val updatedAt: OffsetDateTime,
)
