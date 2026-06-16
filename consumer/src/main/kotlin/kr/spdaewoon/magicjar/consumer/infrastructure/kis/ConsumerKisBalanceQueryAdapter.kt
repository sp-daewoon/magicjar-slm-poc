package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
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
 * Consumer-side KIS `inquire-balance` adapter for risk capital fallback.
 *
 * The API module already has the user-facing implementation. This consumer copy keeps
 * the module boundary clean while letting automatic trading evaluate the configured risk
 * percentage even when the short-lived account cache has expired.
 */
@Component
class ConsumerKisBalanceQueryAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisAuthAdapter: KisAuthAdapter,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
    @Value("\${magicjar.kis.balance-rate-limit-backoff-millis:1000}")
    private val rateLimitBackoffMillis: Long = 1000L,
    // F10 (2026-06-12) — rate limit 시 재시도 횟수(KIS per-TR 1/s 고려 — 간격 1s+ 권장).
    // 1회 retry 로는 burst 구간에서 부족 → default 2회. 모든 재시도 소진 시 KisBalanceRateLimitedException.
    @Value("\${magicjar.kis.balance-rate-limit-max-retries:2}")
    private val rateLimitMaxRetries: Int = 2,
) : KisBalanceQueryPort {
    private val log = LoggerFactory.getLogger(ConsumerKisBalanceQueryAdapter::class.java)

    override fun fetchFullBalance(
        userId: String,
        accountType: AccountType,
    ): KisAccountBalance =
        fetchFullBalanceInternal(
            userId = userId,
            accountType = accountType,
            priority = KisCallPriority.USER_PATH,
            backoffMillis = rateLimitBackoffMillis,
            maxRetries = rateLimitMaxRetries,
            logLabel = "consumer KIS 잔고조회",
        )

    /**
     * account availability snapshot refresh 전용 조회.
     *
     * 주문 hot path 와 경쟁하지 않도록 BACKFILL priority를 사용하고, rate-limit 계열은 500ms × 2회까지만
     * 재시도한다. 실패하면 snapshot을 갱신하지 않고 기존 주문/KIS reject 흐름을 최종 진실로 둔다.
     */
    fun fetchFullBalanceForAvailabilityRefresh(
        userId: String,
        accountType: AccountType,
    ): KisAccountBalance =
        fetchFullBalanceInternal(
            userId = userId,
            accountType = accountType,
            priority = KisCallPriority.BACKFILL,
            backoffMillis = AVAILABILITY_REFRESH_BACKOFF_MS,
            maxRetries = AVAILABILITY_REFRESH_MAX_RETRIES,
            logLabel = "account availability refresh",
        )

    private fun fetchFullBalanceInternal(
        userId: String,
        accountType: AccountType,
        priority: KisCallPriority,
        backoffMillis: Long,
        maxRetries: Int,
        logLabel: String,
    ): KisAccountBalance {
        val cred = loadCredential(userId, accountType)
        val accessToken = kisAuthAdapter.getAccessToken(userId, accountType)
        val trId = if (accountType == AccountType.REAL) TR_ID_REAL else TR_ID_MOCK
        val baseUrl = if (accountType == AccountType.REAL) kisBaseProd else kisBaseVps

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null

        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val response =
                requestBalanceResponse(
                    cred = cred,
                    accessToken = accessToken,
                    appKey = appKeyStr,
                    secretKey = secretKeyStr,
                    trId = trId,
                    baseUrl = baseUrl,
                    userId = userId,
                    retryAttempt = 0,
                    priority = priority,
                    backoffMillis = backoffMillis,
                    maxRetries = maxRetries,
                )

            val snapshot =
                KisAccountBalance(
                    userId = userId,
                    accountType = accountType,
                    balance = parseOutput2(response),
                    holdings = parseOutput1(response),
                    fetchedAt = Instant.now(),
                )
            log.info(
                "{} 성공: user={} type={} holdings={}건 netAsset={}",
                logLabel,
                maskUserId(userId),
                accountType.name,
                snapshot.holdings.size,
                snapshot.balance.netAssetValue,
            )
            snapshot
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun requestBalanceResponse(
        cred: ConsumerUserKisCredentialJpaEntity,
        accessToken: String,
        appKey: String,
        secretKey: String,
        trId: String,
        baseUrl: String,
        userId: String,
        retryAttempt: Int,
        priority: KisCallPriority,
        backoffMillis: Long,
        maxRetries: Int,
    ): Map<String, Any> {
        var acquiredToken = false
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(priority, timeoutMillis = RATE_LIMIT_ACQUIRE_TIMEOUT_MS, trId = trId)
            acquiredToken = true

            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(parseHost(baseUrl))
                            .port(parsePort(baseUrl))
                            .path(INQUIRE_BALANCE_PATH)
                            .queryParam("CANO", cred.accountNumber)
                            .queryParam("ACNT_PRDT_CD", cred.accountPrdtCd)
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
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(TIMEOUT)
                    .block() as? Map<String, Any>

            kisRateLimiterPort.onSuccess()
            signalSent = true
            response ?: error("KIS 잔고조회 응답 없음 — userId=$userId")
        } catch (e: WebClientResponseException) {
            val errorClass = classifyKisError(e.statusCode.value(), e.responseBodyAsString)
            if (errorClass is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
                // F10 — rate limit 은 일시적이므로 backoff 후 재시도(default 2회 — burst 구간 흡수).
                // 기다렸다 다시 잔고를 재확인하는 것이 0원 fabricate 보다 우선(결재 §1).
                if (retryAttempt < maxRetries) {
                    val nextAttempt = retryAttempt + 1
                    log.warn(
                        "consumer KIS 잔고조회 Rate Limit — {}ms backoff 후 재시도 {}/{}: user={} status={}",
                        backoffMillis,
                        nextAttempt,
                        maxRetries,
                        maskUserId(userId),
                        e.statusCode.value(),
                    )
                    sleepBeforeRateLimitRetry(backoffMillis)
                    return requestBalanceResponse(
                        cred = cred,
                        accessToken = accessToken,
                        appKey = appKey,
                        secretKey = secretKey,
                        trId = trId,
                        baseUrl = baseUrl,
                        userId = userId,
                        retryAttempt = nextAttempt,
                        priority = priority,
                        backoffMillis = backoffMillis,
                        maxRetries = maxRetries,
                    )
                }
                // 모든 재시도 소진 — rate limit 임을 타입으로 드러내 호출부(snapshot provider)가
                // stale-cache·skip 으로 분기하게 한다(0 fabricate 금지 · F10).
                log.error(
                    "consumer KIS 잔고조회 Rate Limit — 재시도 {}회 모두 소진: user={} status={}",
                    maxRetries,
                    maskUserId(userId),
                    e.statusCode.value(),
                )
                throw KisBalanceRateLimitedException(userId, maxRetries, e)
            }
            log.error(
                "consumer KIS 잔고조회 실패: user={} status={} body={}",
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] ConsumerKisBalanceQuery acquire 후 통계 시그널 미발행 — drift risk: user={} retryAttempt={}",
                    maskUserId(userId),
                    retryAttempt,
                )
            }
        }
    }

    private fun sleepBeforeRateLimitRetry(backoffMillis: Long) {
        val sleepMillis = backoffMillis.coerceAtLeast(0L)
        if (sleepMillis > 0L) {
            Thread.sleep(sleepMillis)
        }
    }

    private fun loadCredential(
        userId: String,
        accountType: AccountType,
    ): ConsumerUserKisCredentialJpaEntity =
        credentialRepository
            .findAllByAccountType(accountType.name)
            .firstOrNull { it.id.userId == userId }
            ?: throw IllegalStateException("KIS 자격증명 없음 — userId=$userId type=${accountType.name}")

    @Suppress("UNCHECKED_CAST")
    internal fun parseOutput1(response: Map<String, Any>): List<AccountHolding> {
        val output1 = response["output1"] as? List<Map<String, Any>> ?: return emptyList()
        return output1.mapNotNull { item ->
            val code = item["pdno"]?.toString() ?: return@mapNotNull null
            val qty = item["hldg_qty"]?.toString()?.toIntOrNull() ?: 0
            if (qty <= 0) return@mapNotNull null
            AccountHolding(
                code = code,
                name = item["prdt_name"]?.toString().orEmpty(),
                quantity = qty,
                avgPrice = bd(item["pchs_avg_pric"]),
                currentPrice = bd(item["prpr"]),
                evaluationAmount = bd(item["evlu_amt"]),
                profitLoss = bd(item["evlu_pfls_amt"]),
                profitLossRate = bd(item["evlu_pfls_rt"]),
                orderableQuantity = item["ord_psbl_qty"]?.toString()?.toIntOrNull(),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseOutput2(response: Map<String, Any>): AccountBalance {
        val raw = response["output2"]
        val first =
            when (raw) {
                is List<*> -> raw.firstOrNull() as? Map<String, Any>
                is Map<*, *> -> raw as Map<String, Any>
                else -> null
            } ?: throw IllegalStateException("KIS inquire-balance output2 응답 부재 — mock 합성 0 LOCK")

        return AccountBalance(
            cashBalance = bd(first["dnca_tot_amt"]),
            cashD1 = bd(first["nxdy_excc_amt"]),
            cashD2 = bd(first["prvs_rcdl_excc_amt"]),
            totalEvaluation = bd(first["tot_evlu_amt"]),
            netAssetValue = bd(first["nass_amt"]),
            totalPurchaseAmount = bd(first["pchs_amt_smtl_amt"]),
            totalHoldingEvaluation = bd(first["evlu_amt_smtl_amt"]),
            totalProfitLoss = bd(first["evlu_pfls_smtl_amt"]),
            assetIncreaseDecrease = bdOrNull(first["asst_icdc_amt"]),
            assetIncreaseDecreaseRate = bdOrNull(first["asst_icdc_erng_rt"]),
            fetchedAt = Instant.now(),
        )
    }

    private fun bd(v: Any?): BigDecimal = v?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun bdOrNull(v: Any?): BigDecimal? = v?.toString()?.toBigDecimalOrNull()

    private fun parseHost(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore(":")

    private fun parsePort(baseUrl: String): Int =
        baseUrl
            .removePrefix("https://")
            .substringAfter(":", "")
            .toIntOrNull() ?: 443

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    companion object {
        private val TIMEOUT = Duration.ofSeconds(10)
        private const val RATE_LIMIT_ACQUIRE_TIMEOUT_MS = 3000L
        private const val AVAILABILITY_REFRESH_BACKOFF_MS = 500L
        private const val AVAILABILITY_REFRESH_MAX_RETRIES = 2
        private const val INQUIRE_BALANCE_PATH = "/uapi/domestic-stock/v1/trading/inquire-balance"
        private const val TR_ID_REAL = "TTTC8434R"
        private const val TR_ID_MOCK = "VTTC8434R"
    }
}

/**
 * KIS 잔고조회가 rate limit(EGW00201/EGW00215 등)으로 **재시도까지 모두 소진**됐음을 드러내는 타입
 * (F10 hotfix · 2026-06-12). 일반 조회 실패와 구분해 호출부([kr.spdaewoon.magicjar.consumer.risk
 * .KisCapitalSnapshotProviderAdapter])가 stale-cache → skip 으로 분기하게 한다(0 fabricate 금지).
 */
class KisBalanceRateLimitedException(
    userId: String,
    retries: Int,
    cause: Throwable,
) : RuntimeException("KIS 잔고조회 rate limit — 재시도 ${retries}회 소진 (userId=$userId)", cause)
