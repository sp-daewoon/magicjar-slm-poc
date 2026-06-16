package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitItem
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitPort
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitSnapshot
import kr.spdaewoon.magicjar.domain.account.port.KisRealizedProfitSummary
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * KIS 주식잔고조회_실현손익 read-only adapter.
 *
 * 공식 예제:
 * - `/uapi/domestic-stock/v1/trading/inquire-balance-rlz-pl`, `TTTC8494R`
 *
 * 공식 예제에는 모의 TR_ID가 없어 REAL 계좌 reconcile 전용으로만 연다. 주문 hot path에서는 호출하지 않는다.
 */
@Component
class KisRealizedProfitAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) : KisRealizedProfitPort {
    private val log = LoggerFactory.getLogger(KisRealizedProfitAdapter::class.java)

    override fun fetchRealizedProfit(
        userId: String,
        mode: TradingMode,
    ): KisRealizedProfitSnapshot {
        require(mode == TradingMode.REAL) {
            "KIS 실현손익 조회는 공식 예제 기준 REAL 전용입니다 — mode=$mode"
        }

        val cred = loadCredential(userId)
        val accessToken = kisAuthAdapter.getAccessToken(userId, AccountType.REAL)

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val response = fetchAllPages(cred, accessToken, appKeyStr, secretKeyStr, userId)
            KisRealizedProfitSnapshot(
                userId = userId,
                mode = mode,
                items = parseItems(response.output1),
                summary = parseSummary(response.output2),
                fetchedAt = Instant.now(),
            )
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private fun fetchAllPages(
        cred: ConsumerUserKisCredentialJpaEntity,
        accessToken: String,
        appKey: String,
        secretKey: String,
        userId: String,
    ): RealizedProfitRawResponse {
        val output1 = mutableListOf<Map<String, String>>()
        val output2 = mutableListOf<Map<String, String>>()
        var fk100 = ""
        var nk100 = ""
        var trCont = ""

        repeat(MAX_PAGE_DEPTH) { depth ->
            val page =
                requestPage(
                    cred = cred,
                    accessToken = accessToken,
                    appKey = appKey,
                    secretKey = secretKey,
                    userId = userId,
                    fk100 = fk100,
                    nk100 = nk100,
                    trCont = trCont,
                )
            output1 += page.output1
            output2 += page.output2

            val nextTrCont = page.trCont
            if (nextTrCont !in CONTINUATION_HEADERS) {
                return RealizedProfitRawResponse(output1 = output1, output2 = output2)
            }
            fk100 = page.ctxAreaFk100
            nk100 = page.ctxAreaNk100
            trCont = NEXT_PAGE_TR_CONT

            if (depth == MAX_PAGE_DEPTH - 1) {
                log.warn("KIS 실현손익 조회 페이지 제한 도달: user={}", maskUserId(userId))
            }
        }

        return RealizedProfitRawResponse(output1 = output1, output2 = output2)
    }

    @Suppress("UNCHECKED_CAST")
    private fun requestPage(
        cred: ConsumerUserKisCredentialJpaEntity,
        accessToken: String,
        appKey: String,
        secretKey: String,
        userId: String,
        fk100: String,
        nk100: String,
        trCont: String,
    ): RealizedProfitPageResponse {
        var acquiredToken = false
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = RATE_LIMIT_ACQUIRE_TIMEOUT_MS, trId = TR_ID)
            acquiredToken = true

            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(parseHost(kisBaseProd))
                            .port(parsePort(kisBaseProd))
                            .path(PATH)
                            .queryParam("CANO", cred.accountNumber)
                            .queryParam("ACNT_PRDT_CD", cred.accountPrdtCd)
                            .queryParam("AFHR_FLPR_YN", "N")
                            .queryParam("OFL_YN", "")
                            .queryParam("INQR_DVSN", "02")
                            .queryParam("UNPR_DVSN", "01")
                            .queryParam("FUND_STTL_ICLD_YN", "N")
                            .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                            .queryParam("PRCS_DVSN", "01")
                            .queryParam("COST_ICLD_YN", "N")
                            .queryParam("CTX_AREA_FK100", fk100)
                            .queryParam("CTX_AREA_NK100", nk100)
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", TR_ID)
                    .header("tr_cont", trCont)
                    .header("custtype", "P")
                    .retrieve()
                    .toEntity(Map::class.java)
                    .timeout(TIMEOUT)
                    .block()
                    ?: error("KIS 실현손익 조회 응답 없음 — userId=$userId")

            kisRateLimiterPort.onSuccess()
            signalSent = true

            val body = response.body as? Map<String, Any?> ?: error("KIS 실현손익 조회 body 형식 오류")
            val rtCd = body["rt_cd"]?.toString()
            if (rtCd != "0") {
                error("KIS 실현손익 조회 실패: rt_cd=$rtCd msg=${body["msg1"]}")
            }

            RealizedProfitPageResponse(
                output1 = rows(body["output1"]),
                output2 = rows(body["output2"]),
                ctxAreaFk100 = body["ctx_area_fk100"]?.toString().orEmpty(),
                ctxAreaNk100 = body["ctx_area_nk100"]?.toString().orEmpty(),
                trCont = response.headers.getFirst("tr_cont").orEmpty(),
            )
        } catch (e: WebClientResponseException) {
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn(
                "KIS 실현손익 조회 HTTP 실패: user={} status={} body={}",
                maskUserId(userId),
                e.statusCode.value(),
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            if (acquiredToken && !signalSent) {
                log.warn("[KisRateLimit] realized-profit acquire 후 통계 시그널 미발행 가능 — user={}", maskUserId(userId))
            }
        }
    }

    internal fun parseItems(rows: List<Map<String, String>>): List<KisRealizedProfitItem> =
        rows.mapNotNull { row ->
            val code = row["pdno"]?.ifBlank { null } ?: return@mapNotNull null
            KisRealizedProfitItem(
                code = code,
                name = row["prdt_name"]?.ifBlank { null },
                holdingQty = row["hldg_qty"]?.toIntOrNull(),
                orderableQty = row["ord_psbl_qty"]?.toIntOrNull(),
                purchaseAmount = row["pchs_amt"]?.toBigDecimalOrNull(),
                evaluationAmount = row["evlu_amt"]?.toBigDecimalOrNull(),
                evaluationProfitLoss = row["evlu_pfls_amt"]?.toBigDecimalOrNull(),
                realizedProfitLoss = row["rlzt_pfls"]?.toBigDecimalOrNull(),
                realizedReturnRate = row["rlzt_erng_rt"]?.toBigDecimalOrNull(),
                realEvaluationProfitLoss = row["real_evlu_pfls"]?.toBigDecimalOrNull(),
                raw = row,
            )
        }

    internal fun parseSummary(rows: List<Map<String, String>>): KisRealizedProfitSummary? {
        val row = rows.firstOrNull() ?: return null
        return KisRealizedProfitSummary(
            cashBalance = bd(row["dnca_tot_amt"]),
            cashD1 = bd(row["nxdy_excc_amt"]),
            cashD2 = bd(row["prvs_rcdl_excc_amt"]),
            totalEvaluation = bd(row["tot_evlu_amt"]),
            netAssetValue = bd(row["nass_amt"]),
            totalEvaluationProfitLoss = bd(row["evlu_pfls_smtl_amt"]),
            realizedProfitLoss = bd(row["rlzt_pfls"]),
            realizedReturnRate = bd(row["rlzt_erng_rt"]),
            realEvaluationProfitLoss = bd(row["real_evlu_pfls"]),
            realEvaluationReturnRate = bd(row["real_evlu_pfls_erng_rt"]),
            raw = row,
        )
    }

    private fun loadCredential(userId: String): ConsumerUserKisCredentialJpaEntity =
        credentialRepository
            .findAllByAccountType(AccountType.REAL.name)
            .firstOrNull { it.id.userId == userId }
            ?: throw IllegalStateException("KIS 실현손익 조회 자격증명 없음 — userId=${maskUserId(userId)} type=REAL")

    private fun rows(raw: Any?): List<Map<String, String>> =
        when (raw) {
            is List<*> -> raw.mapNotNull { (it as? Map<*, *>)?.toStringMap() }
            is Map<*, *> -> listOf(raw.toStringMap())
            else -> emptyList()
        }

    private fun Map<*, *>.toStringMap(): Map<String, String> =
        entries.associate { (key, value) -> key.toString() to (value?.toString() ?: "") }

    private fun bd(value: String?): BigDecimal? = value?.toBigDecimalOrNull()

    private fun parseHost(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore(":")

    private fun parsePort(baseUrl: String): Int =
        baseUrl
            .removePrefix("https://")
            .substringAfter(":", "")
            .toIntOrNull() ?: 443

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private data class RealizedProfitRawResponse(
        val output1: List<Map<String, String>>,
        val output2: List<Map<String, String>>,
    )

    private data class RealizedProfitPageResponse(
        val output1: List<Map<String, String>>,
        val output2: List<Map<String, String>>,
        val ctxAreaFk100: String,
        val ctxAreaNk100: String,
        val trCont: String,
    )

    companion object {
        const val PATH = "/uapi/domestic-stock/v1/trading/inquire-balance-rlz-pl"
        const val TR_ID = "TTTC8494R"
        private val TIMEOUT = Duration.ofSeconds(10)
        private val CONTINUATION_HEADERS = setOf("M", "F")
        private const val NEXT_PAGE_TR_CONT = "N"
        private const val MAX_PAGE_DEPTH = 10
        private const val RATE_LIMIT_ACQUIRE_TIMEOUT_MS = 3000L
    }
}
