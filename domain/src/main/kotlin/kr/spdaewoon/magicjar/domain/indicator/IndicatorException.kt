package kr.spdaewoon.magicjar.domain.indicator

/**
 * 지표 계산 도메인 예외.
 *
 * spec §6-2 LOCK · ADR 0021 §2-1 정합.
 */
sealed class IndicatorException(
    message: String,
) : RuntimeException(message) {
    /**
     * 지원하는 source가 등록되지 않은 spec 호출.
     */
    class UnsupportedSpecException(
        name: String,
    ) : IndicatorException("No source supports $name")

    /**
     * 지표 계산에 필요한 캔들 개수가 부족 (예: rsi(period=14)에 candles.size < 14).
     */
    class InsufficientCandlesException(
        required: Int,
        actual: Int,
    ) : IndicatorException("Insufficient candles: required=$required, actual=$actual")

    /**
     * source 내부 계산 실패 (산술 오류 · 산술 NaN 등).
     */
    class CalculationFailureException(
        spec: IndicatorSpec,
        cause: Throwable,
    ) : IndicatorException("Calculation failed for ${spec.name}: ${cause.message}")
}
