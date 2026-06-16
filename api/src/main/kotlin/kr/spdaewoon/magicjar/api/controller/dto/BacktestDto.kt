package kr.spdaewoon.magicjar.api.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

// ─── 사이징 정책 요청 DTO (ADR 0121 §2-A — add-only, 미지정 시 AllCapital 회귀 0) ──────────

/**
 * 포지션 사이징 정책 요청 DTO (ADR 0121 §2-A LOCK).
 *
 * **미지정(null)이면 [kr.spdaewoon.magicjar.domain.backtest.SizingPolicy.AllCapital]** — 기존 호출 회귀 0.
 * [positionPercent]를 주면 [kr.spdaewoon.magicjar.domain.backtest.SizingPolicy.FixedFractional]로 매핑한다
 * (1슬롯 = min(initialCapital × positionPercent/100, maxPositionAmount ?: ∞) 고정 슬롯 — 실전 RiskConfig 정합).
 *
 * @param positionPercent 1포지션당 가용 자본 비율 (%) — 0.01~100.00. null이면 AllCapital(전액).
 * @param maxConcurrent   동시 보유 상한 — 1~50 (1차: G3 판정 맥락 메타 echo, 동시보유 시뮬은 post-1차).
 * @param maxPositionAmount 1포지션 절대 상한 (KRW) — null=비활성. min(percent, amount) 적용.
 */
@Schema(description = "포지션 사이징 정책 요청 (ADR 0121 §2-A · 미지정 시 AllCapital 전액)")
data class SizingPolicyRequestDto(
    @field:Schema(description = "1포지션당 가용 자본 비율 % (0.01~100.00). null이면 AllCapital(전액).", example = "10.00")
    val positionPercent: BigDecimal? = null,
    @field:Schema(description = "동시 보유 포지션 상한 (1~50). 1차는 G3 판정 맥락 메타 echo.", example = "5")
    val maxConcurrent: Int = 1,
    @field:Schema(description = "1포지션 절대 상한 KRW (null=비활성, min(percent, amount) 적용)", example = "1000000")
    val maxPositionAmount: BigDecimal? = null,
)

/**
 * 사이징 정책 echo DTO (ADR 0121 §2-2 — G3 판정 맥락 명시).
 *
 * 응답에 어떤 사이징으로 시뮬했는지(AllCapital / FixedFractional 슬롯)를 echo해 MDD 게이트 판정 맥락을 노출한다.
 */
@Schema(description = "적용 사이징 정책 echo (G3 MDD 판정 맥락)")
data class SizingPolicyEchoDto(
    @field:Schema(description = "사이징 유형 (ALL_CAPITAL · FIXED_FRACTIONAL)", example = "FIXED_FRACTIONAL")
    val type: String,
    @field:Schema(description = "1포지션 자본 비율 % (FIXED_FRACTIONAL일 때, 아니면 null)", example = "10.00")
    val positionPercent: BigDecimal? = null,
    @field:Schema(description = "동시 보유 상한 (FIXED_FRACTIONAL일 때, 아니면 null)", example = "5")
    val maxConcurrent: Int? = null,
    @field:Schema(description = "1포지션 절대 상한 KRW (지정 시, 아니면 null)", example = "1000000")
    val maxPositionAmount: BigDecimal? = null,
)

// ─── 요청 DTO ────────────────────────────────────────────────────────────────

/**
 * 백테스트 실행 요청 DTO (ADR 0056 §5-1 LOCK — Swagger 4단 어노테이션 정합).
 */
@Schema(description = "백테스트 실행 요청")
data class BacktestRunRequest(
    @field:Schema(description = "전략 ID", example = "12", required = true)
    val strategyId: Long,
    @field:Schema(description = "유니버스 종목코드 리스트", example = "[\"005930\",\"000660\"]", required = true)
    val universe: List<String>,
    @field:Schema(description = "캔들 인터벌 (1m · 3m · 5m · 15m · 30m · 90m · 1h · 4h · 12h · 1d · 1wk · 1mo · 1y)", example = "1d")
    val interval: String = "1d",
    @field:Schema(description = "시작일 (포함, yyyy-MM-dd)", example = "2024-01-01", required = true)
    val from: String,
    @field:Schema(description = "종료일 (포함, yyyy-MM-dd)", example = "2025-01-01", required = true)
    val to: String,
    @field:Schema(description = "초기 자본 (KRW)", example = "10000000", required = true)
    val initialCapital: BigDecimal,
    @field:Schema(description = "세금 모델 (korean_default · none)", example = "korean_default")
    val taxModel: String = "korean_default",
    @field:Schema(description = "수수료 모델 (kis_direct · none)", example = "kis_direct")
    val commissionModel: String = "kis_direct",
    @field:Schema(description = "슬리피지 모델 (fixed_0.05 · none)", example = "fixed_0.05")
    val slippageModel: String = "fixed_0.05",
    @field:Schema(description = "가격 모드 (RAW · ADJUSTED)", example = "ADJUSTED")
    val priceAdjustmentMode: String = "ADJUSTED",
    @field:Schema(
        description =
            "포지션 사이징 정책 (ADR 0121 §2-A · 미지정 시 AllCapital 전액 — 기존 회귀 0). " +
                "positionPercent를 주면 고정 슬롯 FixedFractional로 시뮬해 MDD를 분산 포트폴리오에 근사 환산.",
        required = false,
    )
    val sizingPolicy: SizingPolicyRequestDto? = null,
)

// ─── 응답 DTO ────────────────────────────────────────────────────────────────

/**
 * 백테스트 실행 응답 DTO (ADR 0056 §5-1 LOCK).
 */
@Schema(description = "백테스트 실행 응답")
data class BacktestRunResponse(
    @field:Schema(description = "결과 상태 (SUCCESS · ABORTED)", example = "SUCCESS")
    val status: String,
    @field:Schema(description = "매매 기록")
    val trades: List<BacktestTradeDto>,
    @field:Schema(description = "포지션 기록 (종료 + 미종료)")
    val positions: List<BacktestPositionDto>,
    @field:Schema(description = "최종 자본 (KRW)", example = "11500000")
    val finalCapital: BigDecimal,
    @field:Schema(description = "성과 지표")
    val performance: BacktestPerformanceDto?,
    @field:Schema(description = "abort 사유 (status=ABORTED 시)", example = "TIMEOUT_QUOTA_EXCEEDED")
    val abortReason: String? = null,
    @field:Schema(description = "실행 소요 시간 (ms)", example = "3210")
    val durationMs: Long,
    @field:Schema(description = "적용 사이징 정책 echo (ADR 0121 §2-2 — G3 MDD 판정 맥락)")
    val sizingPolicy: SizingPolicyEchoDto,
)

/**
 * 매매 기록 DTO.
 */
@Schema(description = "백테스트 매매 기록")
data class BacktestTradeDto(
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "매매 방향 (BUY · SELL)", example = "BUY")
    val side: String,
    @field:Schema(description = "거래 수량", example = "10")
    val quantity: Long,
    @field:Schema(description = "슬리피지 적용 체결가", example = "70350.00")
    val executedPrice: BigDecimal,
    @field:Schema(description = "슬리피지 적용 전 캔들 종가", example = "70000.00")
    val rawPrice: BigDecimal,
    @field:Schema(description = "체결 시각 (ISO 8601)", example = "2024-01-03T00:00:00Z")
    val executedAt: String,
    @field:Schema(description = "수수료 (KRW)", example = "1155")
    val commission: BigDecimal,
    @field:Schema(description = "세금 (KRW)", example = "1260")
    val tax: BigDecimal,
    @field:Schema(description = "슬리피지 금액 (KRW)", example = "3500")
    val slippage: BigDecimal,
)

/**
 * 포지션 DTO.
 */
@Schema(description = "백테스트 포지션")
data class BacktestPositionDto(
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "보유 수량", example = "10")
    val quantity: Long,
    @field:Schema(description = "평균 매수가", example = "70350.00")
    val avgEntryPrice: BigDecimal,
    @field:Schema(description = "오픈 시각", example = "2024-01-03T00:00:00Z")
    val openedAt: String,
    @field:Schema(description = "종료 시각 (null=현재 보유)", example = "2024-06-01T00:00:00Z")
    val closedAt: String?,
    @field:Schema(description = "실현 손익 (KRW)", example = "125000")
    val realizedPnl: BigDecimal,
)

/**
 * 성과 지표 DTO.
 */
@Schema(description = "백테스트 성과 지표")
data class BacktestPerformanceDto(
    @field:Schema(description = "총수익률", example = "0.1500")
    val totalReturn: BigDecimal,
    @field:Schema(description = "연환산 수익률 (CAGR)", example = "0.1260")
    val cagr: BigDecimal,
    @field:Schema(description = "최대 낙폭 MDD (양수)", example = "0.0820")
    val maxDrawdown: BigDecimal,
    @field:Schema(description = "Sharpe 비율 (n<30 시 null)", example = "1.2340")
    val sharpeRatio: BigDecimal?,
    @field:Schema(description = "승률", example = "0.6000")
    val winRate: BigDecimal,
    @field:Schema(description = "총 거래 수", example = "15")
    val totalTrades: Int,
    @field:Schema(description = "이익 거래 수", example = "9")
    val winningTrades: Int,
    @field:Schema(description = "손실 거래 수", example = "6")
    val losingTrades: Int,
    @field:Schema(description = "평균 이익 금액 (KRW)", example = "250000")
    val avgWin: BigDecimal,
    @field:Schema(description = "평균 손실 금액 (KRW, 양수)", example = "120000")
    val avgLoss: BigDecimal,
    @field:Schema(description = "Profit Factor (sumLoss==0 시 null)", example = "3.1250")
    val profitFactor: BigDecimal?,
    @field:Schema(description = "매매 빈도 = 거래 수 / 영업일수", example = "0.0600")
    val tradingFrequency: BigDecimal,
)

// ─── 과최적화 탐지 — IS/OOS 분할 (ADR 0117 §2-6 · spec §3-R3, add-only) ──────────

/**
 * 과최적화 탐지(IS/OOS 분할) 요청 DTO.
 *
 * 백테스트 기간을 학습(In-Sample)·검증(Out-of-Sample) 두 구간으로 나눠 각각 백테스트하고,
 * 검증 구간에서도 돈이 되는지(과최적화 아닌지)를 판정한다. [BacktestRunRequest]에 분할일만 더한 형태.
 */
@Schema(description = "과최적화 탐지(IS/OOS 분할 백테스트) 요청")
data class OverfitDetectRequest(
    @field:Schema(description = "전략 ID", example = "12", required = true)
    val strategyId: Long,
    @field:Schema(description = "유니버스 종목코드 리스트", example = "[\"005930\"]", required = true)
    val universe: List<String>,
    @field:Schema(description = "캔들 인터벌", example = "1d")
    val interval: String = "1d",
    @field:Schema(description = "시작일 (포함, yyyy-MM-dd)", example = "2023-01-01", required = true)
    val from: String,
    @field:Schema(description = "종료일 (포함, yyyy-MM-dd)", example = "2025-01-01", required = true)
    val to: String,
    @field:Schema(
        description = "IS/OOS 분할일 (yyyy-MM-dd, from < split ≤ to). 앞=In-Sample, 뒤=Out-of-Sample. 보통 기간의 70% 지점.",
        example = "2024-08-01",
        required = true,
    )
    val outOfSampleSplit: String,
    @field:Schema(description = "초기 자본 (KRW)", example = "10000000", required = true)
    val initialCapital: BigDecimal,
    @field:Schema(description = "세금 모델 (korean_default · none)", example = "korean_default")
    val taxModel: String = "korean_default",
    @field:Schema(description = "수수료 모델 (kis_direct · none)", example = "kis_direct")
    val commissionModel: String = "kis_direct",
    @field:Schema(description = "슬리피지 모델 (fixed_0.05 · none)", example = "fixed_0.05")
    val slippageModel: String = "fixed_0.05",
    @field:Schema(description = "가격 모드 (RAW · ADJUSTED)", example = "ADJUSTED")
    val priceAdjustmentMode: String = "ADJUSTED",
    @field:Schema(
        description =
            "포지션 사이징 정책 (ADR 0121 §2-A · 미지정 시 AllCapital). copy로 IS/OOS 두 run에 자동 전파. " +
                "신 G3(FixedFractional 10%/5 기준 MDD)는 positionPercent 지정 시 측정.",
        required = false,
    )
    val sizingPolicy: SizingPolicyRequestDto? = null,
)

/**
 * 과최적화 탐지 응답 DTO — IS/OOS 성과 + degradation + 판정.
 */
@Schema(description = "과최적화 탐지(IS/OOS) 응답")
data class OverfitDetectResponse(
    @field:Schema(description = "In-Sample(학습) 구간 성과")
    val inSample: BacktestPerformanceDto,
    @field:Schema(description = "Out-of-Sample(검증) 구간 성과")
    val outOfSample: BacktestPerformanceDto,
    @field:Schema(
        description = "수익 저하율 = (IS.return - OOS.return) / IS.return. 클수록 과최적화. IS≤0이면 null.",
        example = "0.4200",
    )
    val returnDegradation: BigDecimal?,
    @field:Schema(description = "Sharpe 저하 (IS.sharpe - OOS.sharpe). 어느 한쪽 null이면 null.", example = "0.6500")
    val sharpeDegradation: BigDecimal?,
    @field:Schema(
        description = "판정 통과 — OOS 기대값>0 && 거래≥100 && MDD≤8% (spec §3-R3 LOCK). false면 과최적화 의심/표본 부족.",
        example = "false",
    )
    val passed: Boolean,
    @field:Schema(description = "판정 분해 — OOS 총수익률 > 0", example = "true")
    val oosExpectancyPositive: Boolean,
    @field:Schema(description = "판정 분해 — OOS 거래수 ≥ 100", example = "false")
    val oosTradesSufficient: Boolean,
    @field:Schema(description = "판정 분해 — OOS MDD ≤ 8%", example = "true")
    val oosDrawdownControlled: Boolean,
    @field:Schema(description = "degradation null 사유 (IS_RETURN_NON_POSITIVE) 또는 null", example = "null")
    val degradationUndefinedReason: String?,
)

// ─── 파라미터 민감도 분석 (ADR 0117 §2-6 · spec §3-R3, add-only) ─────────────────

/**
 * 민감도 분석 요청 DTO — 청산 파라미터 1개를 그리드로 sweep.
 */
@Schema(description = "파라미터 민감도 분석 요청")
data class SensitivityAnalyzeRequest(
    @field:Schema(description = "전략 ID", example = "12", required = true)
    val strategyId: Long,
    @field:Schema(description = "유니버스 종목코드 리스트", example = "[\"005930\"]", required = true)
    val universe: List<String>,
    @field:Schema(description = "캔들 인터벌", example = "1d")
    val interval: String = "1d",
    @field:Schema(description = "시작일 (포함, yyyy-MM-dd)", example = "2024-01-01", required = true)
    val from: String,
    @field:Schema(description = "종료일 (포함, yyyy-MM-dd)", example = "2025-01-01", required = true)
    val to: String,
    @field:Schema(description = "초기 자본 (KRW)", example = "10000000", required = true)
    val initialCapital: BigDecimal,
    @field:Schema(
        description = "민감도 대상 청산 파라미터 (fixed_stop_loss_pct · take_profit_pct · trailing_stop_pct)",
        example = "fixed_stop_loss_pct",
        required = true,
    )
    val paramName: String,
    @field:Schema(description = "변동값 그리드 (% 단위, 점 ≥ 2)", example = "[2, 3, 4, 5, 6]", required = true)
    val grid: List<BigDecimal>,
    @field:Schema(description = "세금 모델", example = "korean_default")
    val taxModel: String = "korean_default",
    @field:Schema(description = "수수료 모델", example = "kis_direct")
    val commissionModel: String = "kis_direct",
    @field:Schema(description = "슬리피지 모델", example = "fixed_0.05")
    val slippageModel: String = "fixed_0.05",
    @field:Schema(description = "가격 모드", example = "ADJUSTED")
    val priceAdjustmentMode: String = "ADJUSTED",
    @field:Schema(
        description = "포지션 사이징 정책 (ADR 0121 §2-A · 미지정 시 AllCapital). copy로 그리드 각 run에 자동 전파.",
        required = false,
    )
    val sizingPolicy: SizingPolicyRequestDto? = null,
)

/**
 * 민감도 분석 응답 DTO.
 */
@Schema(description = "파라미터 민감도 분석 응답")
data class SensitivityAnalyzeResponse(
    @field:Schema(description = "민감도 대상 파라미터", example = "fixed_stop_loss_pct")
    val paramName: String,
    @field:Schema(description = "그리드 각 점 (파라미터값 + 성과)")
    val points: List<SensitivityPointDto>,
    @field:Schema(
        description = "고원(plateau) 여부 — 인접 점 성과 변동이 작으면 true(robust), 크면 false(과최적화 의심).",
        example = "true",
    )
    val isPlateau: Boolean,
    @field:Schema(description = "그리드 중 수익률 최대 파라미터값", example = "4")
    val bestParamValue: BigDecimal,
    @field:Schema(description = "수익률 스프레드 = max - min (클수록 민감)", example = "0.0850")
    val returnSpread: BigDecimal,
    @field:Schema(description = "인접 점 평균 상대 변동 (plateau 판정 근거, 작을수록 고원)", example = "0.1200")
    val meanAdjacentVariation: BigDecimal,
)

/**
 * 민감도 그리드 한 점 DTO.
 */
@Schema(description = "민감도 그리드 점")
data class SensitivityPointDto(
    @field:Schema(description = "파라미터값 (%)", example = "3")
    val paramValue: BigDecimal,
    @field:Schema(description = "그 값으로 실행한 성과 (Aborted면 null)")
    val performance: BacktestPerformanceDto?,
    @field:Schema(description = "성과 null 사유 (NO_CANDLE_DATA 등) 또는 null", example = "null")
    val skippedReason: String?,
)
