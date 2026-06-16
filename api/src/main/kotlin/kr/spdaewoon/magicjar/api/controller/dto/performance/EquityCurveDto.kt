package kr.spdaewoon.magicjar.api.controller.dto.performance

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/**
 * Equity Curve 시계열 DTO — ADR 0072 §6-5 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "Equity Curve 시계열 — 일별 누적 P&L (차트 source)")
data class EquityCurveDto(
    @Schema(description = "조회 시작 일자", example = "2026-04-14")
    val from: LocalDate,
    @Schema(description = "조회 종료 일자", example = "2026-05-14")
    val to: LocalDate,
    @Schema(description = "일별 Equity Curve 점 시계열 (거래일만 포함, 거래 없는 날 생략)")
    val points: List<EquityPointDto>,
)
