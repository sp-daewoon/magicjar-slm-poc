package kr.spdaewoon.magicjar.domain.indicator

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * 지표 계산 요청 명세.
 *
 * - [name]: 지표 ID (예: "rsi", "macd", "bb"). 80지표 카탈로그 정합 — `_workspace/strategy/indicator-catalog.md`.
 * - [params]: 지표 파라미터 (예: {"period": 14}). default 값은 [IndicatorCatalog]의 메타데이터 정합.
 *
 * ADR 0021 §2-1 정합. spec §6-2 LOCK.
 */
data class IndicatorSpec(
    val name: String,
    val params: Map<String, Any>,
) {
    /**
     * paramsHash — Redis 캐시 key prefix용 SHA-256 16자리 hex.
     *
     * 정규화: key 알파벳 정렬 + `{key}={value}` join + SHA-256 + 16자리 hex prefix.
     * BigDecimal 값은 toPlainString() 결과 사용 (scientific notation 회피).
     *
     * spec §5-3 LOCK 정합.
     */
    fun paramsHash(): String {
        val sorted =
            params.entries
                .sortedBy { it.key }
                .joinToString("|") { (k, v) ->
                    val valueStr =
                        when (v) {
                            is BigDecimal -> v.toPlainString()
                            else -> v.toString()
                        }
                    "$k=$valueStr"
                }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(sorted.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    /**
     * 파라미터 값을 Int로 추출 (없으면 default).
     */
    fun intParam(
        key: String,
        default: Int,
    ): Int =
        when (val v = params[key]) {
            null -> default
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }

    /**
     * 파라미터 값을 BigDecimal로 추출 (없으면 default).
     */
    fun bdParam(
        key: String,
        default: BigDecimal,
    ): BigDecimal =
        when (val v = params[key]) {
            null -> default
            is BigDecimal -> v
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> v.toBigDecimalOrNull() ?: default
            else -> default
        }

    /**
     * 파라미터 값을 String으로 추출 (없으면 default).
     *
     * order_block `detection`("bos"/"engulfing") · `range`("wick"/"body") 같은
     * 열거형(enum-like) 문자열 파라미터 추출용. null·blank → default.
     */
    fun stringParam(
        key: String,
        default: String,
    ): String =
        when (val v = params[key]) {
            null -> default
            is String -> v.ifBlank { default }
            else -> v.toString()
        }
}
