package kr.spdaewoon.magicjar.api.controller.dto.performance

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Equity Curve 점 DTO — ADR 0072 §6-5 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "Equity Curve 점 — 거래일별 P&L + 누적 equity")
data class EquityPointDto(
    @Schema(description = "거래일", example = "2026-05-14")
    val tradeDate: LocalDate,
    @Schema(description = "당일 실현 손익 (원)", example = "150000.00")
    val dailyPnl: BigDecimal,
    @Schema(description = "누적 실현 손익 (equity, 원)", example = "1250000.00")
    val cumulativePnl: BigDecimal,
    @Schema(description = "당일 거래 횟수", example = "3")
    val tradeCount: Int,
)
