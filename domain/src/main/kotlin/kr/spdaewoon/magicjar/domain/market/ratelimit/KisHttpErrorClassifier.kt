package kr.spdaewoon.magicjar.domain.market.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * KIS REST 호출 실패 응답 분류 sealed interface (진단 PR #639 P0 hotfix).
 *
 * ### 결함 배경 (진단 PR #639)
 * KIS OpenAPI는 rate-limit 위반(EGW00201)을 **HTTP 500** body로 wrap.
 * 5+ KIS adapter가 `if (e.statusCode.value() == 429) onTpsExceeded()` 단일 가드만 사용 →
 * EGW00201 영원히 누락 → Bucket4j 자기 통계 sync 못 함 → 다음 호출도 또 EGW00201 악순환.
 *
 * 본 sealed interface + [classifyKisError] extension fun이 단일 권위 분류 경로.
 *
 * ### 진보 페르소나 (ADR 0087) 설계 사유
 * - 💡 **sealed interface** — caller `when` 분기 exhaustive 강제. 신규 분류 추가 시 컴파일 게이트
 * - 🔄 **단일 helper 추출** — 5+ adapter 동일 catch 분기 산재 = DRY 위반 해소. 본 fun 1곳만 정정하면 5+ adapter 자동 갱신
 * - ✨ **domain 모듈 LOCK** — webflux 의존 0 (statusCode·responseBody primitive로 받음). api·consumer·batch 3 모듈 모두 의존 가능
 *
 * ### 모듈 경계 (ADR 0004 헥사고날)
 * - 본 파일은 domain 모듈 → webflux 의존 0 LOCK (input은 raw `Int` statusCode + raw `String?` body)
 * - WebClientResponseException 변환은 caller adapter 책임 (`classifyKisError(e.statusCode.value(), e.responseBodyAsString)`)
 *
 * ### KIS msg_cd 카탈로그 (KIS-first raw 정합)
 * - EGW00201 — 초당 거래건수 초과 (rate limit family, 가장 빈번)
 * - EGW00121 — 일일 거래건수 초과 (rate limit family)
 * - EGW00133 — 토큰 만료/오류 (auth)
 * - (Phase 3 후속에서 추가 매트릭스 확장)
 *
 * 출처: KIS OpenAPI 공식 — https://apiportal.koreainvestment.com/intro
 */
sealed interface KisErrorClass {
    /** EGW00201 · EGW00121 — TPS / 일일 한도 초과 (Bucket4j onTpsExceeded 시그널 대상) */
    data object RateLimit : KisErrorClass

    /** EGW00133 — 토큰 만료/오류 (token cache invalidate 트리거) */
    data object AuthError : KisErrorClass

    /** 5xx 일반 — KIS 서버 오류 (rate-limit 미해당) */
    data object NetworkError : KisErrorClass

    /** 4xx 일반 — KIS 요청 오류 (rate-limit 미해당) */
    data object ClientError : KisErrorClass

    /** rt_cd·msg_cd 매트릭스 외 — log 누적 후 Phase 3에서 case 분기 추가 */
    data class Unknown(
        val rtCd: String?,
        val msgCd: String?,
    ) : KisErrorClass
}

/**
 * KIS HTTP 응답 status + body 분류 — adapter catch 블록 단일 진입점.
 *
 * 알고리즘 (우선순위):
 *   1. body msg_cd가 EGW00201·EGW00121 → RateLimit (HTTP 500/429 무관)
 *   2. body msg_cd가 EGW00133 → AuthError
 *   3. HTTP 429 → RateLimit (body 파싱 실패 case fallback)
 *   4. HTTP 5xx → NetworkError
 *   5. HTTP 4xx → ClientError
 *   6. 그 외 → Unknown(rt_cd, msg_cd)
 *
 * body 파싱 graceful — 비-JSON 응답·null·empty 모두 안전. 파싱 실패 시 status fallback 의존.
 *
 * @param statusCode HTTP status (WebClientResponseException.statusCode.value())
 * @param responseBody raw body string (WebClientResponseException.responseBodyAsString)
 * @return KisErrorClass sealed 분류
 */
fun classifyKisError(
    statusCode: Int,
    responseBody: String?,
): KisErrorClass {
    val parsed = parseKisErrorBody(responseBody)
    val msgCd = parsed.msgCd
    val rtCd = parsed.rtCd

    // 1순위 — msg_cd 매트릭스 (HTTP status 무관)
    when (msgCd) {
        "EGW00201", "EGW00121" -> return KisErrorClass.RateLimit
        "EGW00133" -> return KisErrorClass.AuthError
    }

    // 2순위 — HTTP status fallback
    return when {
        statusCode == 429 -> KisErrorClass.RateLimit
        statusCode in 500..599 -> KisErrorClass.NetworkError
        statusCode in 400..499 -> KisErrorClass.ClientError
        else -> KisErrorClass.Unknown(rtCd, msgCd)
    }
}

/**
 * KIS 응답 body raw 파싱 — msg_cd · rt_cd 추출 (graceful).
 *
 * `internal` 가시성 — 단위 테스트 검증 목적 (헌법 §6 함정 무관 · Repository 외부).
 *
 * 비-JSON 응답·null·empty 모두 안전. 파싱 실패 시 KisErrorBody(null, null) 반환.
 */
internal fun parseKisErrorBody(responseBody: String?): KisErrorBody {
    if (responseBody.isNullOrBlank()) return KisErrorBody(null, null)
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        val map = KIS_ERROR_OBJECT_MAPPER.readValue(responseBody, Map::class.java) as Map<String, Any?>
        KisErrorBody(
            rtCd = map["rt_cd"]?.toString(),
            msgCd = map["msg_cd"]?.toString(),
        )
    }.getOrElse { KisErrorBody(null, null) }
}

/** KIS 응답 body 파싱 결과 (msg_cd · rt_cd) — internal DTO */
internal data class KisErrorBody(
    val rtCd: String?,
    val msgCd: String?,
)

/** ObjectMapper 단일 share — body 파싱 hot path 매번 신규 발행 회피 */
private val KIS_ERROR_OBJECT_MAPPER: ObjectMapper = jacksonObjectMapper()
