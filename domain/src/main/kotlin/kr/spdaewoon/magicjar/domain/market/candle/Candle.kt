package kr.spdaewoon.magicjar.domain.market.candle

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 캔들 도메인 (ADR 0019).
 * raw = 수정 전 원가, adj = 수정 주가 (액면분할·배당 반영).
 * source = 데이터 출처 (KIS·CSV 등 — Yahoo Finance 2026-05-11 폐기).
 */
data class Candle(
    val id: Long? = null,
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,
    val openRaw: BigDecimal,
    val highRaw: BigDecimal,
    val lowRaw: BigDecimal,
    val closeRaw: BigDecimal,
    val openAdj: BigDecimal?,
    val highAdj: BigDecimal?,
    val lowAdj: BigDecimal?,
    val closeAdj: BigDecimal?,
    val volume: Long,
    val source: String,
)

/**
 * 캔들 인터벌 (ADR 0019 §candle.interval CHECK).
 */
enum class CandleInterval(
    val code: String,
) {
    ONE_MIN("1m"),
    TWO_MIN("2m"),
    THREE_MIN("3m"),
    FIVE_MIN("5m"),
    FIFTEEN_MIN("15m"),
    THIRTY_MIN("30m"),
    NINETY_MIN("90m"),
    ONE_HOUR("1h"),
    FOUR_HOUR("4h"),
    TWELVE_HOUR("12h"),
    ONE_DAY("1d"),
    ONE_WEEK("1wk"),
    ONE_MONTH("1mo"),
    ONE_YEAR("1y"),
    ;

    companion object {
        fun fromCode(code: String): CandleInterval =
            entries.find { it.code == code }
                ?: throw IllegalArgumentException("Unknown candle interval: $code")
    }
}
