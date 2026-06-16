package kr.spdaewoon.magicjar.domain.indicator

/**
 * 지표 계산 결과.
 *
 * - [spec]: 입력 spec 그대로.
 * - [series]: 시계열 결과 (warm-up 기간 NaN은 클라이언트가 빈 IndicatorPoint로 받음 — 본 spec은 series 길이 ≤ candles.size 보장).
 * - [source]: 계산 source 식별자 (예: "native" · "kis-strategy-builder" · "ta-lib").
 *
 * ADR 0021 §2-1 정합.
 */
data class IndicatorResult(
    val spec: IndicatorSpec,
    val series: List<IndicatorPoint>,
    val source: String,
)
