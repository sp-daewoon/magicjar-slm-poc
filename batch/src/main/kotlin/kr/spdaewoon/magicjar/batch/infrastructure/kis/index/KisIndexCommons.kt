package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import java.math.BigDecimal
import java.time.Duration

/**
 * T-MARKET KIS index adapter 5건 공통 유틸 (ADR 0100 §2-3 LOCK).
 *
 * 5 adapter (FHPUP02100000·FHPUP02120000·FHPUP02140000·HHDFS00000300·HHDFS76240000) 공유:
 *   - WebClient base URL parsing (parseHost·parsePort 패턴 reuse — ApiKisBalanceQueryAdapter 정합)
 *   - timeout 단일 LOCK (10초 — KIS 일반 권장)
 *   - BigDecimal/Long safe parsing (KIS string 응답 graceful 변환)
 *
 * raw 출처 (헌법 §3 KIS-first):
 *   https://github.com/koreainvestment/open-trading-api (5 endpoint examples_llm)
 *
 * 자체 추론 0 LOCK: 본 유틸은 path/header/parsing helper only — 비즈니스 결정 0.
 */
internal object KisIndexCommons {
    val TIMEOUT: Duration = Duration.ofSeconds(10)

    /** 해외 KIS base URL — overseas-price endpoint 공유 (국내와 host 동일) */
    const val OVERSEAS_HOST = "openapi.koreainvestment.com"
    const val OVERSEAS_PORT = 9443

    fun parseHost(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore(":")

    fun parsePort(baseUrl: String): Int =
        baseUrl
            .removePrefix("https://")
            .substringAfter(":", "")
            .toIntOrNull() ?: 443

    /** KIS string 응답 → BigDecimal (빈문자열·null·비숫자 모두 ZERO graceful) */
    fun bd(v: Any?): BigDecimal =
        v
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** nullable BigDecimal — 빈문자열·null → null */
    fun bdOrNull(v: Any?): BigDecimal? =
        v
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.toBigDecimalOrNull()

    fun longOrNull(v: Any?): Long? =
        v
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()

    /**
     * raw response JSON serialization — KIS-first 정합 raw_payload 영속용.
     *
     * 직렬화 실패 시 null 반환 (graceful — 영속 자체 실패 회피).
     */
    fun serializeRawPayload(
        objectMapper: ObjectMapper,
        response: Any?,
        log: Logger,
    ): String? =
        response?.let {
            runCatching { objectMapper.writeValueAsString(it) }
                .getOrElse {
                    log.warn("[KisIndex] raw_payload 직렬화 실패 — null 영속. err={}", it.message)
                    null
                }
        }
}

/**
 * KIS index 어댑터 5건 공유 credential + fallback resolver.
 *
 * env 매트릭스 (T-MARKET Phase 1 LOCK):
 *   - magicjar.kis.index.enabled         (static env credential 사용 여부, default false)
 *   - magicjar.kis.index.app-key
 *   - magicjar.kis.index.app-secret
 *   - magicjar.kis.index.access-token    (사용자별 token 없음 — 시스템 단일 token)
 *   - magicjar.kis.index.base-url        (default prod: https://openapi.koreainvestment.com:9443)
 *   - magicjar.kis.index.use-real-credential (default true — DB REAL credential fallback)
 *
 * static env credential 이 없으면 DB에 등록된 REAL KIS credential 로 토큰을 발급한다.
 * 시세·캔들 조회는 REAL KIS 우선 정책이므로 시장지수도 같은 데이터 path 를 따른다.
 *
 * 완전 비활성은 `magicjar.market.index.sync.enabled=false` 로 Job 전체를 끈다.
 * DB REAL credential 도 없으면 null 반환 (mock 합성 0).
 */
@org.springframework.stereotype.Component
class KisIndexCredentials(
    @org.springframework.beans.factory.annotation.Value("\${magicjar.kis.index.enabled:false}")
    val enabled: Boolean = false,
    @org.springframework.beans.factory.annotation.Value("\${magicjar.kis.index.app-key:}")
    val appKey: String = "",
    @org.springframework.beans.factory.annotation.Value("\${magicjar.kis.index.app-secret:}")
    val appSecret: String = "",
    @org.springframework.beans.factory.annotation.Value("\${magicjar.kis.index.access-token:}")
    val accessToken: String = "",
    @org.springframework.beans.factory.annotation.Value("\${magicjar.kis.index.base-url:https://openapi.koreainvestment.com:9443}")
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    @org.springframework.beans.factory.annotation.Value("\${magicjar.kis.index.use-real-credential:true}")
    val useRealCredential: Boolean = true,
    private val realCredentialResolver: org.springframework.beans.factory.ObjectProvider<KisIndexRealCredentialResolver>? = null,
) {
    /**
     * disabled / credential 비어있음 시 true — caller가 KIS 호출 우회.
     */
    fun shouldSkip(): Boolean = resolve() == null

    fun resolve(): ResolvedKisIndexCredentials? {
        if (enabled && appKey.isNotBlank() && appSecret.isNotBlank() && accessToken.isNotBlank()) {
            return ResolvedKisIndexCredentials(
                appKey = appKey,
                appSecret = appSecret,
                accessToken = accessToken,
                baseUrl = baseUrl,
                source = "static-env",
            )
        }
        if (!useRealCredential) return null
        return realCredentialResolver?.getIfAvailable()?.resolve()
    }

    /**
     * 단위 테스트용 보조 생성자 — Spring 의존 0 fixture.
     */
    companion object {
        fun forTest(
            enabled: Boolean = true,
            appKey: String = "test-app-key",
            appSecret: String = "test-app-secret",
            accessToken: String = "test-token",
            baseUrl: String = "https://openapi.koreainvestment.com:9443",
        ): KisIndexCredentials = KisIndexCredentials(enabled, appKey, appSecret, accessToken, baseUrl)
    }
}

data class ResolvedKisIndexCredentials(
    val appKey: String,
    val appSecret: String,
    val accessToken: String,
    val baseUrl: String,
    val source: String,
)
