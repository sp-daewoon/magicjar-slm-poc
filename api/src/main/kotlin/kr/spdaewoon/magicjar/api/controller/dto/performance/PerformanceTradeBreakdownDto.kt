package kr.spdaewoon.magicjar.api.controller.dto.performance

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 성과 상세 근거 DTO — 실현손익 발생 매도 체결 단위.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Schema(description = "성과 실현손익 상세 — 매도 체결별 근거")
data class PerformanceTradeBreakdownDto(
    @Schema(description = "기간 단위", example = "MONTH")
    val period: String,
    @Schema(description = "기간 시작 일자", example = "2026-05-01")
    val periodStart: LocalDate,
    @Schema(description = "기간 종료 일자", example = "2026-05-22")
    val periodEnd: LocalDate,
    @Schema(description = "상세 row 합산 실현손익", example = "4333.34")
    val totalRealizedPnl: BigDecimal,
    @Schema(description = "실현손익 row 수", example = "2")
    val tradeCount: Int,
    @Schema(description = "매도 체결별 실현손익 상세")
    val items: List<PerformanceTradeDto>,
)

@Schema(description = "성과 실현손익 상세 row")
data class PerformanceTradeDto(
    @Schema(description = "체결 ID", example = "1001")
    val executionId: Long,
    @Schema(description = "주문 ID", example = "101")
    val orderId: Long,
    @Schema(description = "거래일", example = "2026-05-20")
    val tradeDate: LocalDate,
    @Schema(description = "체결 시각 (KST)", example = "2026-05-20T15:24:31+09:00")
    val executedAt: OffsetDateTime,
    @Schema(description = "종목 코드", example = "005930")
    val code: String,
    @Schema(description = "종목명", example = "삼성전자", nullable = true)
    val name: String?,
    @Schema(description = "거래 모드", example = "MOCK", allowableValues = ["MOCK", "REAL"])
    val mode: String,
    @Schema(description = "주문 출처", example = "MANUAL", allowableValues = ["AUTO", "MANUAL"])
    val source: String,
    @Schema(description = "매도 수량", example = "1")
    val executedQty: BigDecimal,
    @Schema(description = "매도 체결가", example = "276000")
    val executedPrice: BigDecimal,
    @Schema(description = "청산 기준 평균 매수가", example = "273833.3333")
    val avgBuyPrice: BigDecimal,
    @Schema(description = "수수료", example = "0")
    val fee: BigDecimal,
    @Schema(description = "세금", example = "0")
    val tax: BigDecimal,
    @Schema(description = "실현손익", example = "2166.67")
    val realizedPnl: BigDecimal,
)
