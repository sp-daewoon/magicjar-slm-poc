package kr.spdaewoon.magicjar.api.controller.risk

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * 리스크 한도 현재 사용량 응답 DTO — GET /api/risk/usage (읽기 전용, frontend 계약 LOCK).
 *
 * ## 목적 — "표시값 ≠ 실제판정" 위험 차단
 * 설정 화면이 "각 한도에 지금 얼마나 가까운지"를 보여줄 때, **게이트가 실제 매수 판정에 쓰는 값과 동일한
 * 소스·산식**으로 내려준다. position 섹션의 totalExposure·totalLimit·buyable 은 매수 사이징(consumer
 * RiskGatewayEvaluator, PR #1011)이 호출하는 domain `RiskBudgetCalculator` 와 동일 함수로 산출된다.
 *
 * ## 모든 금액 필드는 문자열(BigDecimal) — 정밀도 손실·JS Number overflow 회피
 * 원 단위 금액(절대한도·예수금 등)은 [BigDecimal] 직렬화(잭슨 기본 = JSON number)로 내려가며, 큰 금액의
 * JS Number 정밀도 손실은 프런트가 string 파싱으로 처리한다(frontend 계약 합의).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Schema(description = "리스크 한도 현재 사용량 snapshot (게이트 실제 판정값과 동일 소스)")
data class RiskUsageResponse(
    @Schema(
        description =
            "포지션(매수) 한도 사용량 — capital(잔고) 조회 불가여도 절대 null 로 떨어지지 않고 " +
                "config·positions 파생값(absoluteLimit·maxPositionPercent·totalExposure)은 항상 채워 부분 렌더한다. " +
                "순자산(capital) 의존 필드만 null (B 일일손실 카드와 동일 부분 채움 정책).",
        nullable = true,
    )
    val position: PositionUsage?,
    @Schema(description = "일일 손실 한도 사용량 — capital 조회 불가로 percent 환산 불가 시 일부 null")
    val dailyLoss: DailyLossUsage,
    @Schema(description = "동시 보유 포지션 한도 사용량")
    val concurrent: ConcurrentUsage,
    @Schema(description = "전략 거래 빈도 한도 사용량 — strategyId 미지정 시 null", nullable = true)
    val tradeFrequency: TradeFrequencyUsage?,
)

/**
 * 포지션(매수) 한도 사용량 — RiskBudgetCalculator.resolveBuyBudget 와 1:1 (매수 사이징 동일 소스).
 *
 * ## 부분 렌더 — capital 미상 시에도 섹션은 살아 있다 (B 카드 정합)
 * 순자산(capital) 의존 6필드(percentBase·percentLimit·totalLimit·remaining·orderableCash·buyable)는
 * Redis 잔고 cache miss 시 **null** 로 내려가고, config·positions 만으로 산출되는 3필드
 * (absoluteLimit·maxPositionPercent·totalExposure)는 capital 무관하게 **항상 채워진다**. 따라서 프런트는
 * 섹션 전체를 "준비중"으로 가리지 않고 알고 있는 한도값은 즉시 보여줄 수 있다.
 */
@Schema(description = "포지션(매수) 한도 사용량 — 계좌 전체 공유 풀 기준 (capital 미상 시 순자산 의존 필드만 null)")
data class PositionUsage(
    @Schema(description = "단일 포지션 절대 한도 (config.maxPositionAmount, 원) — config 파생, capital 불필요", example = "10000000")
    val absoluteLimit: BigDecimal,
    @Schema(description = "단일 포지션 비율 한도 (config.maxPositionPercent, %) — config 파생, capital 불필요", example = "10.0")
    val maxPositionPercent: BigDecimal,
    @Schema(
        description = "비율 한도 기준 자본 = 순자산 netAssetValue (원) — capital(잔고) 조회 불가 시 null",
        example = "100000000",
        nullable = true,
    )
    val percentBase: BigDecimal?,
    @Schema(
        description = "비율 한도 금액 = 순자산 × maxPositionPercent / 100 (원) — capital(잔고) 조회 불가 시 null",
        example = "10000000",
        nullable = true,
    )
    val percentLimit: BigDecimal?,
    @Schema(
        description = "총 한도 = min(절대, 비율) — 계좌 전체 공유 한도 (원) — capital(잔고) 조회 불가 시 null",
        example = "10000000",
        nullable = true,
    )
    val totalLimit: BigDecimal?,
    @Schema(
        description = "전체 노출 = Σ 보유포지션(avgPrice × quantity), 전 종목·전 전략 매수원가 (원) — positions 파생, capital 불필요",
        example = "9000000",
    )
    val totalExposure: BigDecimal,
    @Schema(
        description = "남은 한도 = (totalLimit − totalExposure).coerceAtLeast(0) (원) — capital(잔고) 조회 불가 시 null",
        example = "1000000",
        nullable = true,
    )
    val remaining: BigDecimal?,
    @Schema(
        description = "주문가능 예수금 = cashD2 fallback cashBalance (원) — capital(잔고) 조회 불가 시 null",
        example = "3000000",
        nullable = true,
    )
    val orderableCash: BigDecimal?,
    @Schema(
        description = "실제 매수가능 = min(remaining, orderableCash) (원) — capital(잔고) 조회 불가 시 null",
        example = "1000000",
        nullable = true,
    )
    val buyable: BigDecimal?,
)

/**
 * 일일 손실 한도 사용량.
 */
@Schema(description = "일일 손실 한도 사용량")
data class DailyLossUsage(
    @Schema(description = "DLL 활성 여부 (dailyLossLimitAmount 또는 Percent 설정)", example = "true")
    val enabled: Boolean,
    @Schema(description = "일일 손실 한도 절대값 (원) — 미설정 시 null", example = "100000", nullable = true)
    val limitAmount: BigDecimal?,
    @Schema(description = "일일 손실 한도 비율 (%) — 미설정 시 null", example = "null", nullable = true)
    val limitPercent: BigDecimal?,
    @Schema(description = "당일 실현 손익 (음수=손실, 원)", example = "-32000")
    val todayRealizedPnl: BigDecimal,
    @Schema(
        description = "손실 한도까지 남은 여유 = (한도 + todayPnl).coerceAtLeast(0). DLL 비활성·percent 환산 불가 시 null",
        example = "68000",
        nullable = true,
    )
    val remaining: BigDecimal?,
)

/**
 * 동시 보유 포지션 한도 사용량.
 */
@Schema(description = "동시 보유 포지션 한도 사용량")
data class ConcurrentUsage(
    @Schema(description = "동시 보유 최대 수 (config.maxConcurrentPositions)", example = "5")
    val limit: Int,
    @Schema(description = "현재 보유 종목 수 (quantity>0)", example = "3")
    val current: Int,
    @Schema(description = "남은 슬롯 = (limit − current).coerceAtLeast(0)", example = "2")
    val remaining: Int,
)

/**
 * 전략 거래 빈도 한도 사용량 (strategyId 지정 시).
 */
@Schema(description = "전략 거래 빈도 한도 사용량")
data class TradeFrequencyUsage(
    @Schema(description = "전략 ID", example = "86")
    val strategyId: Long,
    @Schema(description = "분당 최대 거래 수 (config.maxTradesPerMinute)", example = "3")
    val perMinuteLimit: Int,
    @Schema(description = "최근 1분 거래 수", example = "1")
    val perMinuteUsed: Int,
    @Schema(description = "일별 최대 거래 수 (config.maxTradesPerDay)", example = "50")
    val perDayLimit: Int,
    @Schema(description = "당일(KST) 누적 거래 수", example = "38")
    val perDayUsed: Int,
    @Schema(description = "당일 남은 거래 수 = (perDayLimit − perDayUsed).coerceAtLeast(0)", example = "12")
    val perDayRemaining: Int,
)
