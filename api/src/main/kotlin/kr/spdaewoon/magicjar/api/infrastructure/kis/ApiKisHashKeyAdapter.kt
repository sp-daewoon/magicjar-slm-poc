package kr.spdaewoon.magicjar.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * API module hashkey adapter for user-triggered order cancel.
 *
 * 신규 주문 발행은 consumer KisOrderHttpAdapter가 계속 담당한다. API cancel path는 KIS 취소 호출 전 hashkey가 필요해
 * 같은 raw mapping을 API 모듈에 얇게 둔다.
 */
@Component
class ApiKisHashKeyAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(ApiKisHashKeyAdapter::class.java)

    companion object {
        const val HASHKEY_PATH: String = "/uapi/hashkey"
        private const val TIMEOUT_SEC = 3L
    }

    fun issueHashKey(
        body: Map<String, String>,
        accessToken: String,
        appKey: String,
        appSecret: String,
        accountType: AccountType,
    ): String {
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps
        var signalSent = false
        return try {
            // hashkey 엔드포인트는 tr_id 헤더 없음 — 호출 식별 라벨 "hashkey" (ADR 0116 §2-E 연장 metric).
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = "hashkey")
            val response =
                webClient
                    .post()
                    .uri("$baseUrl$HASHKEY_PATH")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ApiKisHashKeyResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .block()
                    ?: throw IllegalStateException("HASHKEY_RESPONSE_NULL")

            kisRateLimiterPort.onSuccess()
            signalSent = true
            response.hash?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("HASHKEY_HASH_FIELD_NULL")
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 로 분기 통일 (EGW00201 body 검사 포함).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn("KIS hashkey 발급 실패(api): status={} body={}", e.statusCode, e.responseBodyAsString.take(200))
            throw if (e.statusCode.is5xxServerError) e else IllegalStateException("HASHKEY_HTTP_${e.statusCode.value()}", e)
        } finally {
            if (!signalSent) {
                log.warn("[KisRateLimit] api hashkey acquire 후 통계 시그널 미발행 — drift risk")
            }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiKisHashKeyResponse(
    @JsonProperty("HASH")
    val hash: String? = null,
)
