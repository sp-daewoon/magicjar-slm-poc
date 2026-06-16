package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.mcp.McpToolException
import java.math.BigDecimal

/**
 * MCP tool input 파싱 공통 helper — DRY 원칙 적용 (리팩토링맨 페르소나).
 *
 * 모든 helper는 [McpToolException] (httpStatus=400) 한국어 message 로 통일 — 외부 client 친화 UX.
 *
 * ## 사용 예
 * ```kotlin
 * val code = params.requireString("code") { "code는 6자리 종목코드" }
 * val limit = params.optionalInt("limit", default = 100, min = 1, max = 1000)
 * ```
 */
internal object McpParamSupport {
    fun Map<String, Any?>.requireString(
        key: String,
        validator: (String) -> Boolean = { it.isNotBlank() },
        errorMessage: () -> String,
    ): String {
        val raw =
            this[key]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw McpToolException("필수 파라미터 누락: $key")
        if (!validator(raw)) {
            throw McpToolException(errorMessage())
        }
        return raw
    }

    fun Map<String, Any?>.optionalString(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

    fun Map<String, Any?>.requireEnum(
        key: String,
        allowed: Set<String>,
    ): String {
        val value = requireString(key) { "$key 값 누락" }
        if (value !in allowed) {
            throw McpToolException("$key 값이 허용되지 않습니다: '$value' (허용=$allowed)")
        }
        return value
    }

    fun Map<String, Any?>.optionalInt(
        key: String,
        default: Int? = null,
        min: Int? = null,
        max: Int? = null,
    ): Int? {
        val raw = this[key] ?: return default
        val value =
            when (raw) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull() ?: throw McpToolException("$key 정수 변환 실패: '$raw'")
                else -> throw McpToolException("$key 타입 오류: ${raw::class.simpleName}")
            }
        if (min != null && value < min) throw McpToolException("$key 는 $min 이상 (입력=$value)")
        if (max != null && value > max) throw McpToolException("$key 는 $max 이하 (입력=$value)")
        return value
    }

    fun Map<String, Any?>.optionalBigDecimal(
        key: String,
        default: BigDecimal? = null,
    ): BigDecimal? {
        val raw = this[key] ?: return default
        return when (raw) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.toBigDecimalOrNull() ?: throw McpToolException("$key 숫자 변환 실패: '$raw'")
            else -> throw McpToolException("$key 타입 오류: ${raw::class.simpleName}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.optionalIntList(
        key: String,
        minSize: Int? = null,
        maxSize: Int? = null,
    ): List<Int>? {
        val raw = this[key] ?: return null
        val list =
            when (raw) {
                is List<*> ->
                    raw.map {
                        when (it) {
                            is Number -> it.toInt()
                            is String -> it.toIntOrNull() ?: throw McpToolException("$key list 항목 정수 변환 실패: '$it'")
                            else -> throw McpToolException("$key list 항목 타입 오류: ${it?.let { v -> v::class.simpleName } ?: "null"}")
                        }
                    }
                else -> throw McpToolException("$key 는 list 타입 필수")
            }
        if (minSize != null && list.size < minSize) throw McpToolException("$key 최소 ${minSize}개 (입력=${list.size})")
        if (maxSize != null && list.size > maxSize) throw McpToolException("$key 최대 ${maxSize}개 (입력=${list.size})")
        return list
    }

    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.requireStringList(
        key: String,
        minSize: Int? = null,
    ): List<String> {
        val raw = this[key] ?: throw McpToolException("필수 파라미터 누락: $key")
        val list =
            when (raw) {
                is List<*> -> raw.map { it?.toString() ?: throw McpToolException("$key list 항목 null 금지") }
                else -> throw McpToolException("$key 는 list 타입 필수")
            }
        if (minSize != null && list.size < minSize) throw McpToolException("$key 최소 ${minSize}개 (입력=${list.size})")
        return list
    }
}
