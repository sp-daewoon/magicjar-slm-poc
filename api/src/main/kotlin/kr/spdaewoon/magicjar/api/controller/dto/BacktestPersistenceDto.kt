package kr.spdaewoon.magicjar.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

// ─── 영속 조회 응답 DTO (ADR 0061 §4 — C-1 5 endpoint 정합) ──────────────────────

/**
 * 백테스트 실행 목록 항목 DTO (GET /api/backtests 응답용).
 */
@Schema(description = "백테스트 실행 목록 항목")
data class BacktestRunSummaryDto(
    @field:Schema(description = "백테스트 실행 ID", example = "1")
    val id: Long,
    @field:Schema(description = "종목코드 (복수 시 쉼표 구분)", example = "005930")
    val code: String,
    @field:Schema(description = "캔들 인터벌", example = "1d")
    val interval: String,
    @field:Schema(description = "시작일 (ISO 8601)", example = "2024-01-01T00:00:00Z")
    val rangeFrom: String,
    @field:Schema(description = "종료일 (ISO 8601)", example = "2025-01-01T00:00:00Z")
    val rangeTo: String,
    @field:Schema(description = "실행 상태 (PENDING/RUNNING/SUCCEEDED/FAILED)", example = "SUCCEEDED")
    val status: String,
    @field:Schema(description = "완료 시각 (ISO 8601)", example = "2026-05-12T14:10:00Z")
    val completedAt: String?,
    @field:Schema(description = "총수익률", example = "0.1500")
    val totalReturn: BigDecimal?,
    @field:Schema(description = "승률", example = "0.6000")
    val winRate: BigDecimal?,
    @field:Schema(description = "MDD (최대 낙폭)", example = "0.0820")
    val mdd: BigDecimal?,
    @field:Schema(description = "Sharpe 비율", example = "1.2340")
    val sharpe: BigDecimal?,
    @field:Schema(description = "총 거래 수", example = "15")
    val tradeCount: Int?,
    @field:Schema(description = "생성 시각 (ISO 8601)", example = "2026-05-12T14:05:00Z")
    val createdAt: String,
)

/**
 * 백테스트 단건 상세 응답 DTO (GET /api/backtests/{id} 응답용).
 * 메타 + KPI 전체 포함.
 */
@Schema(description = "백테스트 단건 상세 (메타 + KPI)")
data class BacktestRunDetailDto(
    @field:Schema(description = "백테스트 실행 ID", example = "1")
    val id: Long,
    @field:Schema(description = "종목코드 (복수 시 쉼표 구분)", example = "005930")
    val code: String,
    @field:Schema(description = "캔들 인터벌", example = "1d")
    val interval: String,
    @field:Schema(description = "규칙 DSL 요약", example = "strategyId=12 universe=[005930]")
    val ruleDsl: String,
    @field:Schema(description = "시작일 (ISO 8601)", example = "2024-01-01T00:00:00Z")
    val rangeFrom: String,
    @field:Schema(description = "종료일 (ISO 8601)", example = "2025-01-01T00:00:00Z")
    val rangeTo: String,
    @field:Schema(description = "실행 상태", example = "SUCCEEDED")
    val status: String,
    @field:Schema(description = "시작 시각 (ISO 8601)", example = "2026-05-12T14:05:00Z")
    val startedAt: String?,
    @field:Schema(description = "완료 시각 (ISO 8601)", example = "2026-05-12T14:10:00Z")
    val completedAt: String?,
    @field:Schema(description = "총수익률", example = "0.1500")
    val totalReturn: BigDecimal?,
    @field:Schema(description = "승률", example = "0.6000")
    val winRate: BigDecimal?,
    @field:Schema(description = "MDD (최대 낙폭)", example = "0.0820")
    val mdd: BigDecimal?,
    @field:Schema(description = "Sharpe 비율", example = "1.2340")
    val sharpe: BigDecimal?,
    @field:Schema(description = "총 거래 수", example = "15")
    val tradeCount: Int?,
    @field:Schema(description = "포지션 수", example = "8")
    val positionCount: Int?,
    @field:Schema(description = "생성 시각 (ISO 8601)", example = "2026-05-12T14:05:00Z")
    val createdAt: String,
)

/**
 * 백테스트 매매 기록 항목 DTO (GET /api/backtests/{id}/result 응답용).
 */
@Schema(description = "백테스트 매매 기록 (영속 버전)")
data class BacktestTradeResultDto(
    @field:Schema(description = "매매 기록 ID", example = "101")
    val id: Long,
    @field:Schema(description = "체결 시각 (ISO 8601)", example = "2024-03-15T00:00:00Z")
    val ts: String,
    @field:Schema(description = "매매 방향 (BUY/SELL)", example = "BUY")
    val side: String,
    @field:Schema(description = "체결가", example = "70350.0000")
    val price: BigDecimal,
    @field:Schema(description = "거래 수량", example = "10")
    val quantity: Long,
    @field:Schema(description = "수수료+세금 합산 (KRW)", example = "1800")
    val fee: BigDecimal?,
    @field:Schema(description = "신호 규칙 식별자", example = "RSI_CROSS_BUY")
    val ruleSignal: String?,
)

/**
 * 백테스트 포지션 항목 DTO (GET /api/backtests/{id}/result 응답용).
 */
@Schema(description = "백테스트 포지션 (영속 버전)")
data class BacktestPositionResultDto(
    @field:Schema(description = "포지션 ID", example = "201")
    val id: Long,
    @field:Schema(description = "오픈 시각 (ISO 8601)", example = "2024-03-15T00:00:00Z")
    val openTs: String,
    @field:Schema(description = "종료 시각 (ISO 8601)", example = "2024-06-01T00:00:00Z")
    val closeTs: String?,
    @field:Schema(description = "매수 평균가", example = "70350.0000")
    val entryPrice: BigDecimal,
    @field:Schema(description = "매도가", example = "75000.0000")
    val exitPrice: BigDecimal?,
    @field:Schema(description = "보유 수량", example = "10")
    val quantity: Long,
    @field:Schema(description = "실현 손익 (KRW)", example = "125000")
    val pnl: BigDecimal?,
    @field:Schema(description = "수익률", example = "0.0660")
    val returnPct: BigDecimal?,
)

/**
 * 백테스트 결과 시계열 페이지 응답 DTO (GET /api/backtests/{id}/result 응답용).
 */
@Schema(description = "백테스트 결과 시계열 (페이징)")
data class BacktestResultPageDto(
    @field:Schema(description = "매매 기록 페이지")
    val trades: BacktestPageDto<BacktestTradeResultDto>,
    @field:Schema(description = "포지션 기록 페이지")
    val positions: BacktestPageDto<BacktestPositionResultDto>,
)

/**
 * 공용 페이지 래퍼 DTO.
 */
@Schema(description = "페이지 응답 래퍼")
data class BacktestPageDto<T>(
    @field:Schema(description = "데이터 목록")
    val content: List<T>,
    @field:Schema(description = "현재 페이지 (0-indexed)", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기", example = "20")
    val size: Int,
    @field:Schema(description = "전체 항목 수", example = "15")
    val totalElements: Long,
    @field:Schema(description = "전체 페이지 수", example = "1")
    val totalPages: Int,
)

/**
 * 백테스트 실행 + 영속 응답 DTO (POST /api/backtests 응답용).
 * 기존 BacktestRunResponse + runId 추가.
 */
@Schema(description = "백테스트 실행 + 영속 응답 (runId 포함)")
data class BacktestRunPersistResponse(
    @field:Schema(description = "저장된 backtest_run.id", example = "42")
    val runId: Long,
    @field:Schema(description = "결과 상태 (SUCCESS · ABORTED)", example = "SUCCESS")
    val status: String,
    @field:Schema(description = "매매 기록")
    val trades: List<BacktestTradeDto>,
    @field:Schema(description = "포지션 기록")
    val positions: List<BacktestPositionDto>,
    @field:Schema(description = "최종 자본 (KRW)", example = "11500000")
    val finalCapital: java.math.BigDecimal,
    @field:Schema(description = "성과 지표")
    val performance: BacktestPerformanceDto?,
    @field:Schema(description = "abort 사유", example = "TIMEOUT_QUOTA_EXCEEDED")
    val abortReason: String? = null,
    @field:Schema(description = "실행 소요 시간 (ms)", example = "3210")
    val durationMs: Long,
)
