package kr.spdaewoon.magicjar.api.adapter.kis

import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.Instant

/**
 * KIS OAuth tokenP 단발 검증 어댑터 (ADR 0046 §2-3, spec §4-6 정합).
 *
 * 셋업 진입점 — 사용자 1인 호출 TPS 0.001 미만.
 * ADR 0037 RateLimiter · ADR 0038 Redis 분산락 의무 X.
 * 운영용 KisAuthAdapter (토큰 cache + Redis 분산락)는 PR-7+ T-W2에서 별도 구현.
 *
 * KIS 정합 (kis-first 의무 — ADR 0046 §2-3):
 * - 실전 host: https://openapi.koreainvestment.com:9443
 * - 모의 host: https://openapivts.koreainvestment.com:29443
 * - path: /oauth2/tokenP
 * - method: POST, Content-Type: application/json; charset=utf-8
 * - body: {"grant_type":"client_credentials","appkey":"...","appsecret":"..."}
 */
@Component
class KisAuthValidator(
    private val webClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(KisAuthValidator::class.java)

    companion object {
        private const val TOKEN_PATH = "/oauth2/tokenP"
        private const val REAL_HOST = "https://openapi.koreainvestment.com:9443"
        private const val MOCK_HOST = "https://openapivts.koreainvestment.com:29443"
        private val TIMEOUT = Duration.ofSeconds(10)
    }

    fun validate(
        accountType: AccountType,
        appKey: String,
        secretKey: String,
    ): ValidationResult {
        val baseUrl =
            when (accountType) {
                AccountType.REAL -> REAL_HOST
                AccountType.MOCK -> MOCK_HOST
            }

        val requestBody =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to appKey,
                "appsecret" to secretKey,
            )

        return runCatching {
            val response =
                webClient
                    .post()
                    .uri("$baseUrl$TOKEN_PATH")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(TIMEOUT)
                    .block()

            // access_token 필드 존재 여부로 성공 판별
            if (response?.containsKey("access_token") == true) {
                log.info("KIS tokenP 검증 성공 — accountType={}", accountType)
                ValidationResult.Success(tokenIssuedAt = Instant.now())
            } else {
                log.warn("KIS tokenP 응답에 access_token 미포함 — accountType={}", accountType)
                ValidationResult.Failed(httpStatus = HttpStatus.UNAUTHORIZED.value())
            }
        }.getOrElse { ex ->
            when (ex) {
                is WebClientResponseException -> {
                    val status = ex.statusCode.value()
                    log.warn("KIS tokenP 검증 실패 — HTTP {} accountType={}", status, accountType)
                    // KIS 응답 평문을 에러 메시지에 reflect 금지 (ADR 0046 §2-5)
                    ValidationResult.Failed(httpStatus = status)
                }
                is WebClientRequestException -> {
                    log.warn("KIS 연결 실패 (timeout 또는 네트워크 오류) — accountType={}", accountType)
                    ValidationResult.Unavailable
                }
                else -> {
                    log.error("KIS tokenP 검증 중 예상치 못한 오류 — {}", ex.message)
                    ValidationResult.Unavailable
                }
            }
        }
    }
}
