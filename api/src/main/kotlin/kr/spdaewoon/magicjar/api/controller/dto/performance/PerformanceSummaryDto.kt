package kr.spdaewoon.magicjar.api.controller.dto.performance

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 성과 통계 요약 DTO — ADR 0072 §6-4 정합.
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합):
 * @Tag (controller) · @Operation · @Parameter · @Schema
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "성과 통계 요약 — 5 지표 (P&L·MDD·승률·평균 손익비·거래 횟수)")
data class PerformanceSummaryDto(
    @Schema(description = "기간 단위 (DAY·WEEK·MONTH·YEAR·ALL)", example = "MONTH")
    val period: String,
    @Schema(description = "기간 시작 일자", example = "2026-05-01")
    val periodStart: LocalDate,
    @Schema(description = "기간 종료 일자", example = "2026-05-14")
    val periodEnd: LocalDate,
    @Schema(description = "총 실현 손익 (원)", example = "1250000.00")
    val totalPnl: BigDecimal,
    @Schema(description = "최대 낙폭 MDD (%)", example = "8.4321")
    val mddPct: BigDecimal,
    @Schema(description = "승률 (%)", example = "62.50")
    val winRatePct: BigDecimal,
    @Schema(description = "평균 손익비 avg(profit)/abs(avg(loss)) — 손실 0건 시 null", example = "1.8462")
    val profitLossRatio: BigDecimal?,
    @Schema(description = "거래 횟수 (체결 건수)", example = "32")
    val tradeCount: Int,
    @Schema(description = "산정 시각 (KST)", example = "2026-05-14T18:50:00+09:00")
    val computedAt: OffsetDateTime,
    @Schema(description = "캐시 HIT 여부 (Redis TTL 1h)", example = "false")
    val cacheHit: Boolean,
)
