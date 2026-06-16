package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

/**
 * screening 평가 요청 DTO (ADR 0071 §6-1 + spec §6-1).
 *
 * POST /api/screener/evaluate request body.
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "screening 평가 요청")
data class ScreenerEvaluateRequest(
    @Schema(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
    @field:NotNull
    @field:Positive
    val userId: Long,
    @Schema(description = "Strategy ID (저장된 DSL 참조)", example = "42", required = true)
    @field:NotNull
    @field:Positive
    val strategyId: Long,
    @Schema(description = "universe 필터 (옵션 — 기본 KOSPI+KOSDAQ active STOCK+ETF)")
    val filters: ScreenerFilterDto? = null,
    @Schema(
        description = "평가 기준봉 (기본 1d; 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
        example = "1d",
    )
    val interval: String = "1d",
    @Schema(description = "랭킹 최대 결과 수 (1~500, default 50)", example = "50")
    @field:Min(1)
    @field:Max(500)
    val limit: Int = 50,
)
