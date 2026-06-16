package kr.spdaewoon.magicjar.domain.backtest

import java.math.BigDecimal

/**
 * 파라미터 민감도 분석 요청 (ADR 0117 §2-6 · spec §3-R3 시그니처 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * ## 무엇을 측정하나 (과최적화 탐지 — 안정성 lens)
 * 전략의 **핵심 청산 파라미터 1개**(손절%·익절%·트레일링%)를 그리드로 sweep해 각 점의 수익률을 측정한다.
 * 특정 파라미터값에서만 성과가 좋고 **인접값에서 급락**하면 그 우위는 우연(과최적화)일 가능성이 높다.
 * 반대로 넓은 구간에서 고르게 좋으면(고원·plateau) 견고(robust)하다 — 미래에도 재현될 신뢰도가 높다.
 *
 * @param base 기준 백테스트 요청 (이 요청의 [BacktestRequest.exitParamOverride]는 무시되고 그리드 값으로 매 점 치환된다)
 * @param paramName 민감도 대상 식별자 ([ExitParam.paramName] — 예: "fixed_stop_loss_pct"·"take_profit_pct"·"trailing_stop_pct")
 * @param grid 변동값 그리드 (% 단위, 오름차순 권장 — 예: [2, 3, 4, 5, 6]). 비어있을 수 없고 점 수 ≥ 2.
 */
data class SensitivityRequest(
    val base: BacktestRequest,
    val paramName: String,
    val grid: List<BigDecimal>,
) {
    init {
        require(grid.size >= 2) { "sensitivity grid must have >= 2 points: ${grid.size}" }
        require(grid.all { it > BigDecimal.ZERO }) { "sensitivity grid values must be positive: $grid" }
        requireNotNull(ExitParam.fromParamName(paramName)) {
            "unknown paramName '$paramName' — allowed: ${ExitParam.entries.map { it.paramName }}"
        }
    }

    /** 그리드 sweep 대상 청산 파라미터 (검증 통과 보장 — init에서 non-null 확인). */
    val exitParam: ExitParam get() = ExitParam.fromParamName(paramName)!!
}

/**
 * 민감도 분석 결과 (spec §3-R3 시그니처 LOCK).
 *
 * @param points 그리드 각 점의 (파라미터값, 성과)
 * @param isPlateau 고원(robust) 여부 — 인접 점 수익률 분산이 작으면 true(견고), 크면 false(과최적화 의심).
 *   판정식은 [kr.spdaewoon.magicjar.api.service.backtest.SensitivityAnalyzer] 참고(인접 점 수익률 상대 변동 평균 임계).
 * @param bestParamValue 그리드 중 totalReturn이 최대인 파라미터값.
 * @param paramName 민감도 대상 식별자(요청 echo).
 * @param returnSpread 그리드 수익률 스프레드 = max(totalReturn) - min(totalReturn). 클수록 파라미터에 민감(불안정).
 * @param meanAdjacentVariation 인접 점 상대 변동 평균 (plateau 판정 근거값) — 작을수록 고원.
 */
data class SensitivityResult(
    val points: List<SensitivityPoint>,
    val isPlateau: Boolean,
    val bestParamValue: BigDecimal,
    val paramName: String,
    val returnSpread: BigDecimal,
    val meanAdjacentVariation: BigDecimal,
)

/**
 * 민감도 그리드 한 점 (spec §3-R3 LOCK).
 *
 * @param paramValue 파라미터값 (%)
 * @param performance 그 값으로 실행한 백테스트 성과. 캔들 0건 등으로 run이 Aborted면 null([skippedReason] 참고).
 * @param skippedReason performance가 null인 사유 (예: "NO_CANDLE_DATA"·"ABORTED") 또는 null(정상).
 */
data class SensitivityPoint(
    val paramValue: BigDecimal,
    val performance: BacktestPerformance?,
    val skippedReason: String? = null,
)
