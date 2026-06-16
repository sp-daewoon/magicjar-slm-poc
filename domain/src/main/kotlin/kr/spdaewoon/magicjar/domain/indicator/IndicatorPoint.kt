package kr.spdaewoon.magicjar.domain.indicator

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 지표 시계열 1개 포인트.
 *
 * - [time]: 캔들 ts.
 * - [values]: multi-output 지원. 예) MACD → {macd, signal, histogram}, BB → {upper, middle, lower}.
 *   single-output 지표는 keys size=1 (예: {ma=12345.67}).
 *
 * spec §5 multi-output 매트릭스 LOCK 정합.
 */
data class IndicatorPoint(
    val time: Instant,
    val values: Map<String, BigDecimal>,
)
