package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

// ADR 0074 §2-3 정합 — KillSwitch 수동 토글 DTO

// ─── GET /api/system-config/kill-switch 응답 ─────────────────────────────────

/**
 * KillSwitch 현재 상태 응답 (ADR 0074 §2-3-3 정합).
 * enabled=true면 자동 매매 전면 차단 상태.
 */
@Schema(description = "KillSwitch 상태 응답")
data class KillSwitchStateResponse(
    @Schema(description = "KillSwitch 활성 여부 — true=ON(주문 차단), false=OFF(정상)", example = "false")
    val enabled: Boolean,
    @Schema(description = "토글 사유 — 초기 row 없으면 null", example = "drift detected — TOPIX:5930 +3주", nullable = true)
    val reason: String?,
    @Schema(description = "마지막 토글 시각 (UTC ISO-8601). 초기 row 없으면 null", example = "2026-05-14T09:31:21.000Z", nullable = true)
    val updatedAt: Instant?,
    @Schema(
        description = "토글 주체 — AUTO(Reconcile 자동 ON) | USER:<userId>(수동 OFF)",
        example = "AUTO",
        nullable = true,
    )
    val updatedBy: String?,
)

// ─── PUT /api/system-config/kill-switch 요청 ─────────────────────────────────

/**
 * KillSwitch 토글 요청 (ADR 0074 §2-3-3 정합).
 * 사유 입력 필수 (minLength=5, maxLength=500) — ADR 0069 §6 + ADR 0043 §2-3 정합.
 * 60초 cooldown은 frontend 담당 — backend 강제 없음 (ADR 0074 §3-4 §j 채택).
 */
@Schema(description = "KillSwitch 토글 요청 — enabled + 사유 모두 필수")
data class KillSwitchToggleRequest(
    @field:NotNull
    @Schema(description = "토글 후 상태 — true=ON(주문 차단), false=OFF(정상)", example = "false")
    val engaged: Boolean,
    @field:Size(min = 5, max = 64)
    @Schema(
        description = "토글 사유 — 5자 이상 64자 이하 필수 (DB reason 컬럼 VARCHAR(64) 정합)",
        example = "KIS HTS 잔고 확인 완료 — 일치",
    )
    val reason: String,
)
