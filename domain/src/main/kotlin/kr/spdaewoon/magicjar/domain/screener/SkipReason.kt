package kr.spdaewoon.magicjar.domain.screener

/**
 * Screener skip 사유 (ADR 0071 §2-7 G-1 + spec §2-2 SkipReason sealed).
 *
 * universe iterate 중 개별 instrument를 결과에서 제외하는 사유.
 * 사용자에게 정직한 표시(mock 합성 0) — Phase 2 REST 응답 `skippedReasons: Map<String, Int>` 집계.
 *
 * sealed class 분기:
 * - [CandleMissing]: 영속 candle 0건 → "캔들 미수집"
 * - [CandleInsufficient]: 지표 lookback 미충족 → "캔들 부족 (필요: N건, 보유: M건)"
 * - [EvalTimeout]: DSL 평가 timeout (>500ms) → "DSL 평가 timeout"
 * - [EvalError]: DSL 평가 오류 (sandbox auto-disable 또는 unknown exception) → "DSL 평가 오류: {message}"
 * - [HoldOutcome]: BUY/SELL 외 결과 (HOLD) — 매칭 미발생 (skip 매트릭스 표기 0 + 단순 evaluatedCount 합류)
 */
sealed class SkipReason {
    /** Phase 2 REST 응답에 노출되는 사용자용 한국어 label. */
    abstract val label: String

    object CandleMissing : SkipReason() {
        override val label: String = "캔들 미수집"
    }

    data class CandleInsufficient(
        val need: Int,
        val have: Int,
    ) : SkipReason() {
        override val label: String = "캔들 부족"
    }

    object EvalTimeout : SkipReason() {
        override val label: String = "DSL 평가 timeout"
    }

    data class EvalError(
        val message: String,
    ) : SkipReason() {
        override val label: String = "DSL 평가 오류"
    }
}
