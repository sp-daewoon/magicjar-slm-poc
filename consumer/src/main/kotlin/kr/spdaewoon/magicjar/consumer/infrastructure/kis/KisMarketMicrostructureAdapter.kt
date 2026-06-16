package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.port.KisInvestorFlow
import kr.spdaewoon.magicjar.domain.market.port.KisMarketMicrostructurePort
import kr.spdaewoon.magicjar.domain.market.port.KisRecentExecution
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * KIS 종목 미시구조 read-only adapter.
 *
 * 공식 예제:
 * - `inquire-investor` `/uapi/domestic-stock/v1/quotations/inquire-investor`, `FHKST01010900`
 * - `inquire-ccnl` `/uapi/domestic-stock/v1/quotations/inquire-ccnl`, `FHKST01010300`
 */
@Component
class KisMarketMicrostructureAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) : KisMarketMicrostructurePort {
    private val log = LoggerFactory.getLogger(KisMarketMicrostructureAdapter::class.java)

    companion object {
        const val INQUIRE_INVESTOR_PATH = "/uapi/domestic-stock/v1/quotations/inquire-investor"
        const val INQUIRE_CCNL_PATH = "/uapi/domestic-stock/v1/quotations/inquire-ccnl"
        const val TR_ID_INVESTOR = "FHKST01010900"
        const val TR_ID_CCNL = "FHKST01010300"
        private const val TIMEOUT_SECONDS = 10L
    }

    override fun fetchInvestorFlow(
        userId: String,
        mode: TradingMode,
        code: String,
        marketDivCode: String,
    ): List<KisInvestorFlow> =
        parseInvestorRows(
            code = code,
            marketDivCode = marketDivCode,
            rows =
                fetchOutput(
                    userId = userId,
                    mode = mode,
                    path = INQUIRE_INVESTOR_PATH,
                    trId = TR_ID_INVESTOR,
                    marketDivCode = marketDivCode,
                    code = code,
                ),
        )

    override fun fetchRecentExecutions(
        userId: String,
        mode: TradingMode,
        code: String,
        marketDivCode: String,
    ): List<KisRecentExecution> =
        parseExecutionRows(
            code = code,
            marketDivCode = marketDivCode,
            rows =
                fetchOutput(
                    userId = userId,
                    mode = mode,
                    path = INQUIRE_CCNL_PATH,
                    trId = TR_ID_CCNL,
                    marketDivCode = marketDivCode,
                    code = code,
                ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun fetchOutput(
        userId: String,
        mode: TradingMode,
        path: String,
        trId: String,
        marketDivCode: String,
        code: String,
    ): List<Map<String, String>> {
        val accountType = AccountType.REAL
        if (mode != TradingMode.REAL) {
            log.debug(
                "KIS 미시구조 조회는 주문 mode={} 요청이어도 REAL 데이터 키를 사용: trId={} userId={}",
                mode,
                trId,
                maskUserId(userId),
            )
        }
        val cred =
            credentialRepository
                .findAllByAccountType(accountType.name)
                .firstOrNull { it.id.userId == userId }
                ?: error("KIS 미시구조 조회 자격증명 없음 — userId=${maskUserId(userId)} type=${accountType.name}")

        val accessToken = kisAuthAdapter.getAccessToken(userId, accountType)
        val baseUrl = kisBaseProd

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        var acquiredToken = false
        var signalSent = false
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 3000L, trId = trId)
            acquiredToken = true

            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(parseHost(baseUrl))
                            .port(parsePort(baseUrl))
                            .path(path)
                            .queryParam("FID_COND_MRKT_DIV_CODE", marketDivCode)
                            .queryParam("FID_INPUT_ISCD", code)
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", trId)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .block() as? Map<String, Any?>
                    ?: error("KIS 미시구조 조회 응답 없음")

            kisRateLimiterPort.onSuccess()
            signalSent = true

            val rtCd = response["rt_cd"]?.toString()
            if (rtCd != "0") {
                error("KIS 미시구조 조회 실패: trId=$trId rt_cd=$rtCd msg=${response["msg1"]}")
            }

            when (val output = response["output"]) {
                is List<*> -> output.mapNotNull { (it as? Map<*, *>)?.toStringMap() }
                is Map<*, *> -> listOf(output.toStringMap())
                else -> emptyList()
            }
        } catch (e: WebClientResponseException) {
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn(
                "KIS 미시구조 조회 HTTP 실패: trId={} userId={} status={} body={}",
                trId,
                maskUserId(userId),
                e.statusCode,
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
            if (acquiredToken && !signalSent) {
                log.warn(
                    "[KisRateLimit] microstructure acquire 후 통계 시그널 미발행 가능 — trId={} userId={}",
                    trId,
                    maskUserId(userId),
                )
            }
        }
    }

    internal fun parseInvestorRows(
        code: String,
        marketDivCode: String,
        rows: List<Map<String, String>>,
    ): List<KisInvestorFlow> =
        rows.map { row ->
            KisInvestorFlow(
                code = code,
                marketDivCode = marketDivCode,
                businessDate = row["stck_bsop_date"]?.ifBlank { null },
                closePrice = row["stck_clpr"]?.toBigDecimalOrNull(),
                individualNetBuyQty = row["prsn_ntby_qty"]?.toLongOrNull(),
                foreignNetBuyQty = row["frgn_ntby_qty"]?.toLongOrNull(),
                institutionNetBuyQty = row["orgn_ntby_qty"]?.toLongOrNull(),
                individualNetBuyAmount = row["prsn_ntby_tr_pbmn"]?.toBigDecimalOrNull(),
                foreignNetBuyAmount = row["frgn_ntby_tr_pbmn"]?.toBigDecimalOrNull(),
                institutionNetBuyAmount = row["orgn_ntby_tr_pbmn"]?.toBigDecimalOrNull(),
                raw = row,
            )
        }

    internal fun parseExecutionRows(
        code: String,
        marketDivCode: String,
        rows: List<Map<String, String>>,
    ): List<KisRecentExecution> =
        rows.map { row ->
            KisRecentExecution(
                code = code,
                marketDivCode = marketDivCode,
                executionTime = row["stck_cntg_hour"]?.ifBlank { null },
                price = row["stck_prpr"]?.toBigDecimalOrNull(),
                volume = row["cntg_vol"]?.toLongOrNull(),
                relativeStrength = row["tday_rltv"]?.toBigDecimalOrNull(),
                previousDayRate = row["prdy_ctrt"]?.toBigDecimalOrNull(),
                raw = row,
            )
        }

    private fun Map<*, *>.toStringMap(): Map<String, String> =
        entries.associate { (key, value) -> key.toString() to (value?.toString() ?: "") }

    private fun parseHost(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore(":")

    private fun parsePort(baseUrl: String): Int =
        baseUrl
            .removePrefix("https://")
            .substringAfter(":", "")
            .toIntOrNull() ?: 443

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
