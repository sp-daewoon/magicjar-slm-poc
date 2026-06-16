package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.position.port.KisBalance
import kr.spdaewoon.magicjar.domain.position.port.KisBalancePort
import kr.spdaewoon.magicjar.domain.position.port.KisHolding
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * KIS 주식잔고조회 어댑터 (ADR 0042 Bootstrap STAGE 0-1).
 * - 출처: ADR 0042 §2-2 + KIS open-trading-api examples_llm/.../inquire_balance.py
 * - TR_ID: TTTC8434R (실전) / VTTC8434R (모의)
 * - endpoint: /uapi/domestic-stock/v1/trading/inquire-balance
 * - timeout: 5s
 *
 * 메모리 zero-fill 의무: APP_KEY·SECRET_KEY 사용 후 즉시 fill.
 */
@Component
class KisBalanceAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisBalancePort {
    private val log = LoggerFactory.getLogger(KisBalanceAdapter::class.java)

    override fun fetchBalance(
        userId: String,
        accountType: AccountType,
    ): KisBalance {
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: error("KIS 자격증명 없음 — userId=$userId type=${accountType.name}")

        val accessToken = kisAuthAdapter.getAccessToken(userId, accountType)
        val trId = if (accountType == AccountType.REAL) "TTTC8434R" else "VTTC8434R"
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        // D-1 try-finally drift 차단 패턴 (T-D-1):
        //   acquire는 try 안에 이미 있으나 catch 분기 외 path(parse error, RuntimeException 등) drift risk.
        //   signalSent flag로 모든 path 통계 시그널 발행 감시.
        var signalSent = false
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH priority — 잔고 조회 사용자 path)
            kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 3000L, trId = trId)

            @Suppress("UNCHECKED_CAST")
            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(baseUrl.removePrefix("https://").split(":")[0])
                            .port(
                                baseUrl
                                    .removePrefix("https://")
                                    .split(":")
                                    .getOrNull(1)
                                    ?.toIntOrNull() ?: 9443,
                            ).path("/uapi/domestic-stock/v1/trading/inquire-balance")
                            .queryParam("CANO", cred.accountNumber)
                            .queryParam("ACNT_PRDT_CD", "01")
                            .queryParam("AFHR_FLPR_YN", "N")
                            .queryParam("OFL_YN", "")
                            .queryParam("INQR_DVSN", "02")
                            .queryParam("UNPR_DVSN", "01")
                            .queryParam("FUND_STTL_ICLD_YN", "N")
                            .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                            .queryParam("PRCS_DVSN", "00")
                            .queryParam("CTX_AREA_FK100", "")
                            .queryParam("CTX_AREA_NK100", "")
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block() as? Map<String, Any>
                    ?: error("KIS 잔고조회 응답 없음")

            val output1 = response["output1"] as? List<Map<String, Any>> ?: emptyList()
            val holdings =
                output1.mapNotNull { item ->
                    val code = item["pdno"]?.toString() ?: return@mapNotNull null
                    val qty = item["hldg_qty"]?.toString()?.toIntOrNull() ?: 0
                    val avgPrice = item["pchs_avg_pric"]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    if (qty <= 0) return@mapNotNull null
                    KisHolding(code = code, quantity = qty, avgPrice = avgPrice)
                }

            kisRateLimiterPort.onSuccess()
            signalSent = true
            log.info(
                "KIS 잔고조회 성공: user={} type={} holdings={}건",
                maskUserId(userId),
                accountType.name,
                holdings.size,
            )
            KisBalance(
                userId = userId,
                accountType = accountType,
                holdings = holdings,
                fetchedAt = Instant.now(),
            )
        } catch (e: WebClientResponseException) {
            // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                log.warn("KIS 잔고조회 Rate Limit — onTpsExceeded: user={} status={}", maskUserId(userId), e.statusCode.value())
            }
            log.error(
                "KIS 잔고조회 실패: user={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (!signalSent) {
                log.warn(
                    "[KisRateLimit] inquire-balance(consumer) acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): user={}",
                    maskUserId(userId),
                )
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
