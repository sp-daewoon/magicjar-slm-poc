package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCircuitOpenException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
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
 * KIS hashkey 발급 어댑터 (ADR 0084 §2-3 P0-1 — 외부 코드 리뷰 결함 #11 fix).
 *
 * ### 배경
 * 외부 코드 리뷰 13 코멘트 중 #11 — `grep "hashkey\|HashKey\|hash_key" consumer/ api/ domain/` = 0건.
 * KIS POST 주문 호출 시 hashkey 헤더 누락 → REAL 진입 시 KIS REJECT 가능 risk.
 * ADR 0084 P0-1 (HIGH — REAL 진입 즉시 차단 risk) 결정으로 hashkey 어댑터 합류.
 *
 * ### raw 출처 (헌법 §3 KIS-first — 2026-05-18 WebFetch verbatim 시그니처 LOCK)
 * - `set_order_hash_key`: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
 * - order_cash 사용 evidence: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/order_cash/order_cash.py
 *
 * ### raw 본문 시그니처 (2026-05-18 verbatim LOCK)
 * ```python
 * def set_order_hash_key(h, p):
 *     url = f"{getTREnv().my_url}/uapi/hashkey"
 *     res = requests.post(url, data=json.dumps(p), headers=h)
 *     rescode = res.status_code
 *     if rescode == 200:
 *         h["hashkey"] = _getResultObject(res.json()).HASH
 * ```
 *
 * ### endpoint·body·response
 * - endpoint: `POST /uapi/hashkey` (실전 prod · 모의 vps 둘 다 동일 path)
 * - 필요 headers: `authorization` (Bearer access_token) · `appkey` · `appsecret` · `Content-Type: application/json`
 * - body: 주문 body JSON (CANO·ACNT_PRDT_CD·PDNO·ORD_DVSN·ORD_QTY·ORD_UNPR 등 그대로)
 * - response: `{ "BODY": {...}, "HASH": "<hash>" }` — `HASH` 필드 추출
 * - 결과: `hashkey` 헤더에 set → 본 주문 REST 호출 시 함께 전송
 *
 * ### KIS 공식 명시 (raw kis_auth.py inline comment)
 * "currently not mandatory but can be used when concerned about data tampering during API calls"
 * — magicJar는 자본 path 안전성 우선으로 합류 (사용자 ADR 0084 결정).
 *
 * ### 재시도 정책 (헌법 §3 정합)
 * - 401/403 (인증 만료): token refresh 후 1회 재시도 (caller 책임 — 본 어댑터는 IllegalStateException throw)
 * - 5xx (KIS 일시 장애): WebClientResponseException re-throw (caller submitWithRetry 1회 재시도 정합)
 * - 4xx (구조 오류): IllegalStateException throw 즉시 (재시도 무의미)
 *
 * AI 호출 0 — T-O 트랙 정책 정합 (ADR 0068 §8).
 */
@Component
class KisHashKeyAdapter(
    private val webClient: WebClient,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) {
    private val log = LoggerFactory.getLogger(KisHashKeyAdapter::class.java)

    companion object {
        /**
         * raw: `url = f"{getTREnv().my_url}/uapi/hashkey"` (kis_auth.py L? — 2026-05-18 WebFetch LOCK).
         */
        const val HASHKEY_PATH: String = "/uapi/hashkey"
        private const val TIMEOUT_SEC = 10L
        private const val RATE_LIMIT_TIMEOUT_MS = 3000L
    }

    /**
     * 주문 body로부터 hashkey 발급.
     *
     * @param body 주문 REST body Map (CANO·ACNT_PRDT_CD·PDNO·... — KIS REST 본문 그대로)
     * @param accessToken KIS OAuth access_token (Bearer prefix 제외)
     * @param appKey KIS 앱키 (복호화된 평문)
     * @param appSecret KIS 시크릿 (복호화된 평문)
     * @param accountType REAL/MOCK — base URL 분기
     * @return hashkey String (response.HASH)
     *
     * @throws WebClientResponseException 5xx — caller가 호출 단계에 맞춰 처리 (order-cash 전이면 ORDER_NOT_SENT)
     * @throws IllegalStateException 4xx 또는 response null/HASH 부재 — 재시도 무의미
     */
    fun issueHashKey(
        body: Map<String, String>,
        accessToken: String,
        appKey: String,
        appSecret: String,
        accountType: AccountType,
    ): String {
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   acquire를 try 안으로 옮기고 signalSent flag로 통계 시그널 미발행 path 감시.
        var signalSent = false
        var acquiredToken = false
        return try {
            // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH priority, 주문 직전 경로)
            // ADR 0037 초당 10건 한도 강제. timeout 초과 시 KisRateLimitTimeoutException throw.
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = RATE_LIMIT_TIMEOUT_MS, trId = "hashkey")
            acquiredToken = true

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
                    .bodyToMono(KisHashKeyResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .block()
                    ?: throw IllegalStateException("HASHKEY_RESPONSE_NULL")

            kisRateLimiterPort.onSuccess()
            signalSent = true
            response.hash?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("HASHKEY_HASH_FIELD_NULL")
        } catch (e: KisRateLimitTimeoutException) {
            log.warn(
                "KIS hashkey rate-limit acquire timeout — HTTP 미전송: accountType={} timeoutMs={}",
                accountType.name,
                e.timeoutMillis,
            )
            throw e
        } catch (e: KisCircuitOpenException) {
            log.warn(
                "KIS hashkey circuit open — HTTP 미전송: accountType={} openUntil={}",
                accountType.name,
                e.openUntilEpochMillis,
            )
            throw e
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — EGW00201 = HTTP 500 wrap. classifyKisError() 분기 통일.
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS hashkey Rate Limit — 1초 backoff 후 1회 재시도: accountType={} status={}", accountType.name, e.statusCode.value())
                Thread.sleep(1000L)
                return retryHashKey(body, baseUrl, accessToken, appKey, appSecret, accountType)
            }
            // 5xx는 caller submitWithRetry가 재시도 (KisOrderHttpAdapter.submit과 동일 정책)
            // 4xx는 caller에서 IllegalStateException catch → DenyReason.KisException 변환
            log.warn(
                "KIS hashkey 발급 실패: status={} body={} accountType={}",
                e.statusCode,
                e.responseBodyAsString.take(200),
                accountType.name,
            )
            if (e.statusCode.is5xxServerError) {
                throw e // caller 재시도 책임
            }
            // 4xx → IllegalStateException 변환 throw
            throw IllegalStateException("HASHKEY_HTTP_${e.statusCode.value()}", e)
        } finally {
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] hashkey acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): accountType={}",
                    accountType.name,
                )
            }
        }
    }

    /**
     * HTTP 429 1회 재시도 path (spec PR #564 §2-2 LOCK).
     * 재시도도 429 시 onTpsExceeded() + 명시 에러 throw (mock 합성 0 — ADR 0014 정합).
     */
    private fun retryHashKey(
        body: Map<String, String>,
        baseUrl: String,
        accessToken: String,
        appKey: String,
        appSecret: String,
        accountType: AccountType,
    ): String {
        // D-1 try-finally drift 차단 패턴 (T-D-1).
        var signalSent = false
        var acquiredToken = false
        return try {
            // 재시도 전 다시 acquire — 1초 sleep 후이므로 token refill 보장
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = RATE_LIMIT_TIMEOUT_MS, trId = "hashkey")
            acquiredToken = true
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
                    .bodyToMono(KisHashKeyResponse::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .block()
                    ?: throw IllegalStateException("HASHKEY_RESPONSE_NULL")
            kisRateLimiterPort.onSuccess()
            signalSent = true
            response.hash?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("HASHKEY_HASH_FIELD_NULL")
        } catch (e: KisRateLimitTimeoutException) {
            log.warn(
                "KIS hashkey retry rate-limit acquire timeout — HTTP 미전송: accountType={} timeoutMs={}",
                accountType.name,
                e.timeoutMillis,
            )
            throw e
        } catch (e: KisCircuitOpenException) {
            log.warn(
                "KIS hashkey retry circuit open — HTTP 미전송: accountType={} openUntil={}",
                accountType.name,
                e.openUntilEpochMillis,
            )
            throw e
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일.
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                throw IllegalStateException("HASHKEY_RATELIMIT_RETRY_EXHAUSTED_${e.statusCode.value()}", e)
            }
            throw IllegalStateException("HASHKEY_HTTP_${e.statusCode.value()}_RETRY", e)
        } finally {
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] hashkey retry acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): accountType={}",
                    accountType.name,
                )
            }
        }
    }
}

/**
 * KIS hashkey 응답 DTO.
 *
 * raw 응답 본문 (kis_auth.py `_getResultObject(res.json()).HASH` 정합):
 * ```json
 * { "BODY": { ... echo of input ... }, "HASH": "abcd1234..." }
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class KisHashKeyResponse(
    @JsonProperty("HASH")
    val hash: String? = null,
)
